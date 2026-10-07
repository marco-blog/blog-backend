package net.java21.blog.backend.comment.repository;

import static net.java21.blog.backend.comment.domain.QComment.comment;
import static net.java21.blog.backend.post.domain.QPost.post;

import java.time.Instant;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.comment.domain.QComment;
import net.java21.blog.backend.media.domain.QMedia;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 댓글 조회(T194, QueryDSL DTO projection). 작성자는 같은 쿼리의 LEFT JOIN으로 읽어(004 비회원 댓글은 작성자 없음) 댓글 수와
 * 관계없이 쿼리 수가 일정하다.
 * <ul>
 *   <li>글 댓글 목록: 쿼리 1회(답글 포함, 작성순). 트리는 서비스가 만든다.</li>
 *   <li>블로그 관리 목록: 쿼리 2회(목록, 전체 수). 휴지통 글의 댓글은 보이지 않는다(spec "글을 삭제하면 댓글도 보이지 않는다").</li>
 * </ul>
 */
@Repository
public class CommentQueryRepository {

    /** 작성자 별칭. 노출 조각({@code PostExposure})의 기본 별칭 {@code user}와 겹치지 않게 따로 둔다. */
    private static final QUser author = new QUser("author");
    /** 작성자 프로필 이미지(US4). 같은 쿼리의 LEFT JOIN으로 읽는다. */
    private static final QMedia authorMedia = new QMedia("authorMedia");
    /** 답글의 부모(004 비밀 댓글: 부모가 비밀이면 답글도 비밀). */
    private static final QComment parentComment = new QComment("parentComment");

    private final JPAQueryFactory queryFactory;

    public CommentQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 글의 모든 댓글·답글(삭제된 댓글 자리 포함), 작성순. 쿼리 1회. */
    public List<CommentRow> findPostComments(Long postId) {
        return queryFactory
                .select(Projections.constructor(CommentRow.class, comment.id, comment.parent.id, comment.content,
                        comment.status, author.id, author.nickname, authorMedia.mediaKey, comment.createdAt,
                        comment.updatedAt, comment.secret, comment.guestName))
                .from(comment)
                .leftJoin(comment.user, author)
                .leftJoin(author.profileMedia, authorMedia)
                .where(comment.post.id.eq(postId))
                .orderBy(comment.createdAt.asc(), comment.id.asc())
                .fetch();
    }

    /** 블로그 모든 글의 표시되는 댓글, 최신순 페이지. 쿼리 2회. */
    public Page<BlogCommentRow> findBlogComments(Long blogId, Pageable pageable) {
        BooleanExpression where = blogComments(blogId);
        List<BlogCommentRow> rows = selectBlogComments()
                .where(where)
                .orderBy(comment.createdAt.desc(), comment.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(comment.count()).from(comment).join(comment.post, post).where(where)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 대시보드 최근 댓글(최신순 {@code limit}건). 쿼리 1회. */
    public List<BlogCommentRow> findRecentBlogComments(Long blogId, int limit) {
        return selectBlogComments()
                .where(blogComments(blogId))
                .orderBy(comment.createdAt.desc(), comment.id.desc())
                .limit(limit)
                .fetch();
    }

    /** {@code since} 이후 블로그에 달린 표시되는 댓글 수(대시보드 "최근 7일 새 댓글"). 쿼리 1회. */
    public long countBlogCommentsSince(Long blogId, Instant since) {
        Long count = queryFactory.select(comment.count()).from(comment).join(comment.post, post)
                .where(blogComments(blogId), comment.createdAt.goe(since))
                .fetchOne();
        return count == null ? 0 : count;
    }

    private JPAQuery<BlogCommentRow> selectBlogComments() {
        return queryFactory
                .select(Projections.constructor(BlogCommentRow.class, comment.id, comment.content, author.id,
                        author.nickname, authorMedia.mediaKey, comment.createdAt, comment.updatedAt, post.id, post.title,
                        comment.secret, parentComment.secret, comment.guestName))
                .from(comment)
                .join(comment.post, post)
                .leftJoin(comment.parent, parentComment)
                .leftJoin(comment.user, author)
                .leftJoin(author.profileMedia, authorMedia);
    }

    private static BooleanExpression blogComments(Long blogId) {
        return post.blog.id.eq(blogId)
                .and(post.status.ne(PostStatus.DELETED))
                .and(comment.status.eq(CommentStatus.ACTIVE));
    }
}
