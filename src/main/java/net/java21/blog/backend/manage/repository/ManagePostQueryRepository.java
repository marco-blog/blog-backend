package net.java21.blog.backend.manage.repository;

import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.post.domain.QPostDraft.postDraft;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.manage.dto.ManagePostFilter;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 블로그 관리 글 조회·일괄 작업(T157, QueryDSL, 006 FR-100·101). 주인 화면이라 노출 조각({@code PostExposure})을 쓰지 않고
 * 그 블로그의 모든 글을 다룬다(블로그·주인 확인은 서비스가 {@code BlogAccess}로 먼저 한다).
 * <ul>
 *   <li>목록은 DTO projection과 카테고리·작성 중 사본 LEFT JOIN으로 읽어 글 수와 관계없이 쿼리 2회(목록, 전체 수)다.</li>
 *   <li>일괄 작업은 {@code blog_id} 조건이 붙은 집합 UPDATE 한 번이다. 남의 글은 바뀌지 않는다.</li>
 * </ul>
 */
@Repository
public class ManagePostQueryRepository {

    private final JPAQueryFactory queryFactory;

    public ManagePostQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 관리 글 목록. 최신순(휴지통은 최근에 버린 순). 쿼리 2회.
     *
     * @param trashCutoff 휴지통 목록에 넣을 가장 오래된 삭제 시각(지금 - 보관 기간). 이보다 먼저 버린 글은 곧 영구 삭제된다
     */
    public Page<ManagePostRow> findPosts(Long blogId, ManagePostFilter filter, Instant trashCutoff,
            Pageable pageable) {
        BooleanExpression where = conditions(blogId, filter, trashCutoff);
        List<ManagePostRow> rows = selectRows()
                .where(where)
                .orderBy(order(filter))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(post.count()).from(post).leftJoin(post.category, category).where(where)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 대시보드 최근 글(휴지통 제외, 최신순). 쿼리 1회. */
    public List<ManagePostRow> findRecentPosts(Long blogId, int limit) {
        return selectRows()
                .where(post.blog.id.eq(blogId), post.status.ne(PostStatus.DELETED))
                .orderBy(post.id.desc())
                .limit(limit)
                .fetch();
    }

    /** 대시보드 임시저장 글 수(발행 전 글, 휴지통 제외). 쿼리 1회. */
    public long countDrafts(Long blogId) {
        Long count = queryFactory.select(post.count()).from(post)
                .where(post.blog.id.eq(blogId), post.status.eq(PostStatus.DRAFT))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 주어진 글 중 이 블로그의 글 수(휴지통 포함). 일괄 작업의 소유 확인용. 쿼리 1회. */
    public long countOwned(Long blogId, Collection<Long> postIds) {
        Long count = queryFactory.select(post.count()).from(post)
                .where(post.blog.id.eq(blogId), post.id.in(postIds))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 이 블로그 글의 공개 범위를 한 번에 바꾼다. 바뀐 행 수. */
    public long changeVisibility(Long blogId, Collection<Long> postIds, PostVisibility visibility, Instant now) {
        return queryFactory.update(post)
                .set(post.visibility, visibility)
                .set(post.updatedAt, now)
                .where(post.blog.id.eq(blogId), post.id.in(postIds))
                .execute();
    }

    /**
     * 이 블로그 글을 한 번에 휴지통으로(FR-084). 직전 상태를 {@code status_before_delete}에 남기므로 그 대입을 상태 변경보다 먼저 쓴다
     * (MySQL 단일 테이블 UPDATE는 SET을 왼쪽부터 평가한다). 이미 휴지통인 글은 그대로 둔다. 바뀐 행 수.
     */
    public long moveToTrash(Long blogId, Collection<Long> postIds, Instant now) {
        return queryFactory.update(post)
                .set(post.statusBeforeDelete, post.status)
                .set(post.status, PostStatus.DELETED)
                .set(post.deletedAt, now)
                .set(post.updatedAt, now)
                .where(post.blog.id.eq(blogId), post.id.in(postIds), post.status.ne(PostStatus.DELETED))
                .execute();
    }

    /**
     * 이 블로그 글을 한 번에 다른 카테고리로(미분류는 null) 옮기고(006 FR-101 {@code MOVE_CATEGORY}), 작성 중 사본의 카테고리도 맞춘다
     * (다음 발행 때 되돌아가지 않게). 집합 UPDATE 2회. 바뀐 글 수.
     */
    public long moveCategory(Long blogId, Collection<Long> postIds, Category target, Instant now) {
        var update = queryFactory.update(post);
        if (target == null) {
            update.setNull(post.category);
        } else {
            update.set(post.category, target);
        }
        long moved = update.set(post.updatedAt, now)
                .where(post.blog.id.eq(blogId), post.id.in(postIds))
                .execute();
        var drafts = queryFactory.update(postDraft);
        if (target == null) {
            drafts.setNull(postDraft.categoryId);
        } else {
            drafts.set(postDraft.categoryId, target.getId());
        }
        drafts.where(postDraft.postId.in(JPAExpressions.select(post.id).from(post)
                        .where(post.blog.id.eq(blogId), post.id.in(postIds))))
                .execute();
        return moved;
    }

    private JPAQuery<ManagePostRow> selectRows() {
        Expression<Boolean> hasDraft = new CaseBuilder().when(postDraft.postId.isNotNull()).then(true)
                .otherwise(false);
        return queryFactory
                .select(Projections.constructor(ManagePostRow.class,
                        post.id, post.title, post.summary, post.thumbnailUrl, category.id, category.name,
                        post.viewCount, post.commentCount, post.visibility, post.status, post.publishedAt,
                        post.updatedAt, hasDraft, post.deletedAt))
                .from(post)
                .leftJoin(post.category, category)
                .leftJoin(postDraft).on(postDraft.postId.eq(post.id));
    }

    private static BooleanExpression conditions(Long blogId, ManagePostFilter filter, Instant trashCutoff) {
        BooleanExpression where = post.blog.id.eq(blogId);
        if (filter.trash()) {
            where = where.and(post.status.eq(PostStatus.DELETED)).and(post.deletedAt.goe(trashCutoff));
        } else if (filter.status() != null) {
            where = where.and(post.status.eq(filter.status()));
        } else {
            where = where.and(post.status.ne(PostStatus.DELETED));
        }
        if (filter.visibility() != null) {
            where = where.and(post.visibility.eq(filter.visibility()));
        }
        if (filter.categoryId() != null) {
            // 상위 카테고리는 하위 카테고리 글을 포함한다(공개 목록과 같은 규칙, tasks.md 결정 4).
            where = where.and(category.id.eq(filter.categoryId()).or(category.parent.id.eq(filter.categoryId())));
        }
        if (filter.q() != null) {
            where = where.and(post.title.containsIgnoreCase(filter.q()));
        }
        return where;
    }

    private static OrderSpecifier<?>[] order(ManagePostFilter filter) {
        if (filter.trash()) {
            return new OrderSpecifier<?>[] {post.deletedAt.desc(), post.id.desc()};
        }
        return new OrderSpecifier<?>[] {post.id.desc()};
    }
}
