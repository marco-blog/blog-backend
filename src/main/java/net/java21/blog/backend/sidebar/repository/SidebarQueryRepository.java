package net.java21.blog.backend.sidebar.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.comment.domain.QComment.comment;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.List;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.sidebar.dto.SidebarPostResponse;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.stereotype.Repository;

/**
 * 사이드바 데이터 조회(004 FR-060, QueryDSL DTO projection). 노출 조건은 001 {@link PostExposure}만 쓴다. 각 메서드 쿼리 1회.
 * <ul>
 *   <li>최근 글·인기 글: 목록 노출 가능 글.</li>
 *   <li>최근 댓글: 본문 노출 가능 글의 표시되는(ACTIVE) 비밀이 아닌 댓글. 작성자는 LEFT JOIN(비회원 포함).</li>
 * </ul>
 */
@Repository
public class SidebarQueryRepository {

    private static final QUser author = new QUser("author");

    private final JPAQueryFactory queryFactory;

    public SidebarQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 최근 발행 글 {@code limit}편. */
    public List<SidebarPostResponse> findRecentPosts(Long blogId, int limit) {
        return posts(blogId, limit, post.publishedAt.desc(), post.id.desc());
    }

    /** 조회수 많은 글 {@code limit}편(같으면 최근 발행 먼저). */
    public List<SidebarPostResponse> findPopularPosts(Long blogId, int limit) {
        return posts(blogId, limit, post.viewCount.desc(), post.publishedAt.desc(), post.id.desc());
    }

    /** 최근 댓글 {@code limit}개(답글 포함). */
    public List<SidebarCommentRow> findRecentComments(Long blogId, int limit) {
        return queryFactory
                .select(Projections.constructor(SidebarCommentRow.class, comment.id, post.id, post.title,
                        comment.content, author.nickname, comment.guestName, comment.createdAt))
                .from(comment)
                .join(comment.post, post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .leftJoin(comment.user, author)
                .where(blog.id.eq(blogId), PostExposure.bodyVisible(), comment.status.eq(CommentStatus.ACTIVE),
                        comment.secret.isFalse())
                .orderBy(comment.createdAt.desc(), comment.id.desc())
                .limit(limit)
                .fetch();
    }

    private List<SidebarPostResponse> posts(Long blogId, int limit, OrderSpecifier<?>... order) {
        return queryFactory
                .select(Projections.constructor(SidebarPostResponse.class, post.id, post.title, post.publishedAt))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(blog.id.eq(blogId), PostExposure.listable())
                .orderBy(order)
                .limit(limit)
                .fetch();
    }
}
