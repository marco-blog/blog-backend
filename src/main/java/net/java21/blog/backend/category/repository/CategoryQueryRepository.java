package net.java21.blog.backend.category.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.post.domain.QPostDraft.postDraft;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.category.dto.CategoryNode;
import net.java21.blog.backend.post.repository.PostExposure;
import org.springframework.stereotype.Repository;

/**
 * 카테고리 조회·정리(T176, QueryDSL, FR-023·024·026). 트리는 카테고리 수와 무관하게 쿼리 2회(카테고리 목록, 카테고리별 글 수 집계)로 만들고,
 * 삭제 때 글을 미분류로 옮기는 일은 집합 UPDATE로 한다.
 */
@Repository
public class CategoryQueryRepository {

    private final JPAQueryFactory queryFactory;
    private final EntityManager em;

    public CategoryQueryRepository(JPAQueryFactory queryFactory, EntityManager em) {
        this.queryFactory = queryFactory;
        this.em = em;
    }

    /**
     * 블로그의 카테고리 트리. 상위·하위 모두 {@code sort_order}, 같으면 id 순이다. {@code postCount}는 "목록 노출 가능"
     * ({@link PostExposure#listable()}) 글 수이고 상위 노드는 하위 노드의 수를 더한다(tasks.md 결정 4). 쿼리 2회.
     */
    public List<CategoryNode> findTree(Long blogId) {
        List<CategoryRow> rows = findRows(blogId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = countListablePosts(blogId);
        Map<Long, List<CategoryNode>> children = new HashMap<>();
        for (CategoryRow row : rows) {
            if (row.parentId() != null) {
                children.computeIfAbsent(row.parentId(), k -> new ArrayList<>())
                        .add(new CategoryNode(row.id(), row.name(), counts.getOrDefault(row.id(), 0L), List.of()));
            }
        }
        List<CategoryNode> roots = new ArrayList<>();
        for (CategoryRow row : rows) {
            if (row.parentId() == null) {
                List<CategoryNode> kids = List.copyOf(children.getOrDefault(row.id(), List.of()));
                long total = counts.getOrDefault(row.id(), 0L)
                        + kids.stream().mapToLong(CategoryNode::postCount).sum();
                roots.add(new CategoryNode(row.id(), row.name(), total, kids));
            }
        }
        return List.copyOf(roots);
    }

    /** 블로그의 카테고리 행(정렬됨). 쿼리 1회. */
    public List<CategoryRow> findRows(Long blogId) {
        return queryFactory
                .select(Projections.constructor(CategoryRow.class,
                        category.id, category.parent.id, category.name, category.sortOrder))
                .from(category)
                .where(category.blog.id.eq(blogId))
                .orderBy(category.sortOrder.asc(), category.id.asc())
                .fetch();
    }

    /** 카테고리별 목록 노출 가능 글 수(그 카테고리에 직접 속한 글만). 쿼리 1회. */
    private Map<Long, Long> countListablePosts(Long blogId) {
        List<Tuple> tuples = queryFactory
                .select(post.category.id, post.count())
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(blog.id.eq(blogId), post.category.id.isNotNull(), PostExposure.listable())
                .groupBy(post.category.id)
                .fetch();
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Tuple tuple : tuples) {
            Long count = tuple.get(post.count());
            counts.put(tuple.get(post.category.id), count == null ? 0L : count);
        }
        return counts;
    }

    /**
     * 같은 부모(상위면 {@code parentId} null) 아래 같은 이름이 있는지. DB의 UNIQUE는 상위 카테고리끼리를 막지 못하므로 서비스가 먼저
     * 확인한다. 이름 비교는 DB 정렬 규칙을 따른다(MySQL utf8mb4_0900_ai_ci는 대소문자를 구분하지 않는다). 쿼리 1회.
     *
     * @param excludeId 이름을 바꾸는 카테고리 자신(없으면 null)
     */
    public boolean existsSiblingName(Long blogId, Long parentId, String name, Long excludeId) {
        BooleanExpression where = category.blog.id.eq(blogId).and(category.name.eq(name))
                .and(parentId == null ? category.parent.isNull() : category.parent.id.eq(parentId));
        if (excludeId != null) {
            where = where.and(category.id.ne(excludeId));
        }
        return queryFactory.selectOne().from(category).where(where).fetchFirst() != null;
    }

    /** 같은 부모 아래 새 카테고리의 순서(맨 끝). 쿼리 1회. */
    public int nextSortOrder(Long blogId, Long parentId) {
        Integer max = queryFactory.select(category.sortOrder.max())
                .from(category)
                .where(category.blog.id.eq(blogId),
                        parentId == null ? category.parent.isNull() : category.parent.id.eq(parentId))
                .fetchOne();
        return max == null ? 0 : max + 1;
    }

    /** 하위 카테고리 id. 쿼리 1회. */
    public List<Long> findChildIds(Long parentId) {
        return queryFactory.select(category.id).from(category).where(category.parent.id.eq(parentId)).fetch();
    }

    /**
     * 카테고리 삭제 전: 소속 글을 미분류({@code category_id} NULL)로 옮기고(FR-024), 작성 중 사본의 카테고리도 비운다
     * (사본은 외래 키가 없어 발행 때 404가 되지 않게). 집합 UPDATE 2회.
     *
     * @return 미분류로 옮긴 글 수
     */
    public long uncategorize(Collection<Long> categoryIds) {
        long moved = queryFactory.update(post)
                .setNull(post.category)
                .where(post.category.id.in(categoryIds))
                .execute();
        queryFactory.update(postDraft)
                .setNull(postDraft.categoryId)
                .where(postDraft.categoryId.in(categoryIds))
                .execute();
        em.clear();
        return moved;
    }

    /** 이 블로그의 카테고리를 지운다(하위 → 상위 순, 자기 참조 외래 키). DELETE 2회. 지운 수. */
    public long deleteCategories(Long blogId, Collection<Long> categoryIds) {
        long children = queryFactory.delete(category)
                .where(category.blog.id.eq(blogId), category.id.in(categoryIds), category.parent.isNotNull())
                .execute();
        long rest = queryFactory.delete(category)
                .where(category.blog.id.eq(blogId), category.id.in(categoryIds))
                .execute();
        em.clear();
        return children + rest;
    }
}
