package net.java21.blog.backend.post.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.tag.domain.QPostTag;
import org.springframework.stereotype.Repository;

/**
 * 관련 글(002 FR-068, research D8): 같은 블로그의 본문 노출 가능 글({@link PostExposure#bodyVisible()}) 중 기준 글이 아닌 것을
 * 점수(겹치는 태그 수 + 같은 카테고리면 1) 내림차순, 같으면 발행 최신순으로 {@code limit}편. 점수 0인 글은 넣지 않는다.
 * 후보 글의 태그({@code post_tags})를 LEFT JOIN하고 그 태그가 기준 글에도 달렸는지 다시 LEFT JOIN해 세는 쿼리 1회
 * (서버 캐시 없음, 비공개 전환이 바로 반영된다).
 */
@Repository
public class RelatedPostQueryRepository {

    private static final QPostTag candidateTag = new QPostTag("candidateTag");
    private static final QPostTag baseTag = new QPostTag("baseTag");

    private final JPAQueryFactory queryFactory;

    public RelatedPostQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * @param categoryId 기준 글의 카테고리(미분류면 null — 카테고리 점수 없음)
     */
    public List<PostSummaryRow> findRelated(Long blogId, Long postId, Long categoryId, int limit) {
        NumberExpression<Long> sameCategory = categoryId == null
                ? Expressions.numberTemplate(Long.class, "0")
                : new CaseBuilder().when(category.id.eq(categoryId)).then(1L).otherwise(0L);
        NumberExpression<Long> score = baseTag.id.tagId.count().add(sameCategory);
        return queryFactory
                .select(Projections.constructor(PostSummaryRow.class,
                        post.id, post.title, post.summary, post.thumbnailUrl, category.id, category.name,
                        post.viewCount, post.commentCount, post.visibility, post.status, post.publishedAt,
                        post.updatedAt, post.notice))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .leftJoin(post.category, category)
                .leftJoin(candidateTag).on(candidateTag.id.postId.eq(post.id))
                .leftJoin(baseTag).on(baseTag.id.postId.eq(postId), baseTag.id.tagId.eq(candidateTag.id.tagId))
                .where(blog.id.eq(blogId), post.id.ne(postId), PostExposure.bodyVisible())
                .groupBy(post.id, post.title, post.summary, post.thumbnailUrl, category.id, category.name,
                        post.viewCount, post.commentCount, post.visibility, post.status, post.publishedAt,
                        post.updatedAt, post.notice)
                .having(score.gt(0L))
                .orderBy(score.desc(), post.publishedAt.desc(), post.id.desc())
                .limit(limit)
                .fetch();
    }
}
