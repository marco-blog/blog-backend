package net.java21.blog.backend.subscription.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.subscription.domain.QBlogSubscription.blogSubscription;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.media.domain.QMedia;
import net.java21.blog.backend.post.repository.PostExposure;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 구독 피드(002 FR-032, research D2): 회원이 구독한 블로그들의 "목록 노출 가능" 글({@link PostExposure#listable()}),
 * 발행 최신순(같은 시각은 id 내림차순). 정지·탈퇴 회원과 삭제된 블로그의 글은 노출 조각으로 빠진다.
 * DTO projection으로 블로그·작성자·프로필 이미지를 함께 읽어 쿼리 2회(목록, 전체 수)이며 구독 수·글 수와 무관하다.
 */
@Repository
public class SubscriptionFeedQueryRepository {

    private static final QMedia authorMedia = new QMedia("authorMedia");

    private final JPAQueryFactory queryFactory;

    public SubscriptionFeedQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<FeedPostRow> findFeed(long userId, Pageable pageable) {
        BooleanExpression where = PostExposure.listable().and(JPAExpressions.selectOne()
                .from(blogSubscription)
                .where(blogSubscription.id.blogId.eq(blog.id), blogSubscription.id.userId.eq(userId))
                .exists());
        List<FeedPostRow> rows = queryFactory
                .select(Projections.constructor(FeedPostRow.class,
                        post.id, post.title, post.summary, post.thumbnailUrl, category.id, category.name,
                        post.viewCount, post.commentCount, post.visibility, post.status, post.publishedAt,
                        post.updatedAt, blog.handle, blog.title, user.nickname, authorMedia.mediaKey))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .leftJoin(post.category, category)
                .leftJoin(user.profileMedia, authorMedia)
                .where(where)
                .orderBy(post.publishedAt.desc(), post.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory
                .select(post.count())
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(where)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }
}
