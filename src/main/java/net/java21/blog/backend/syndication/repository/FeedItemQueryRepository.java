package net.java21.blog.backend.syndication.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.List;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.repository.PostExposure;
import org.springframework.stereotype.Repository;

/**
 * 블로그 피드(RSS·Atom)에 담을 글(002 FR-045·047, research D6): 블로그의 "목록 노출 가능" 글({@link PostExposure#listable()})을
 * 발행 최신순(같은 시각은 id 내림차순)으로 {@code limit}개. 카테고리 조건은 상위 카테고리면 하위 카테고리 글을 포함한다(001 결정 4).
 * 304 판단용 버전 조회(id·updated_at만)와 본문 조회는 각각 쿼리 1회다.
 */
@Repository
public class FeedItemQueryRepository {

    private final JPAQueryFactory queryFactory;

    public FeedItemQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public List<FeedVersionRow> findVersions(Long blogId, Long categoryId, int limit) {
        return select(Projections.constructor(FeedVersionRow.class, post.id, post.updatedAt), blogId, categoryId,
                limit);
    }

    public List<FeedItemRow> findItems(Long blogId, Long categoryId, int limit) {
        return select(Projections.constructor(FeedItemRow.class, post.id, post.title, post.contentHtml, post.summary,
                post.visibility, post.publishedAt, post.updatedAt, category.name), blogId, categoryId, limit);
    }

    private <T> List<T> select(Expression<T> projection, Long blogId, Long categoryId, int limit) {
        BooleanExpression where = blog.id.eq(blogId).and(PostExposure.listable());
        if (categoryId != null) {
            where = where.and(category.id.eq(categoryId).or(category.parent.id.eq(categoryId)));
        }
        return queryFactory
                .select(projection)
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .leftJoin(post.category, category)
                .where(where)
                .orderBy(post.publishedAt.desc(), post.id.desc())
                .limit(limit)
                .fetch();
    }
}
