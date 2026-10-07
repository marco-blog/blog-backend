package net.java21.blog.backend.admin.content;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.comment.domain.QComment.comment;
import static net.java21.blog.backend.guestbook.domain.QGuestbookEntry.guestbookEntry;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.ArrayList;
import java.util.List;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.admin.content.dto.AdminCommentRow;
import net.java21.blog.backend.admin.content.dto.AdminGuestbookRow;
import net.java21.blog.backend.admin.content.dto.AdminPostRow;
import net.java21.blog.backend.admin.content.dto.ContentAuthor;
import net.java21.blog.backend.blog.domain.QBlog;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.common.persistence.MySqlFullTextFunctions;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 콘텐츠 관리 검색(006 FR-102·104, research A4). 글·댓글·방명록 모두 쿼리 2회(목록 + 개수, 블로그·작성자·글은 같은 쿼리의 JOIN)이고
 * 최신 생성순(PK 역순)이다. 본문 칼럼은 읽지 않는다(댓글·방명록 내용만, 비밀 글은 서비스 응답에서 지운다). 글 제목 검색만 MySQL
 * FULLTEXT({@code ft_posts_title}, {@link MySqlFullTextFunctions#matchTitle})라 H2 시험에서는 그 조건을 쓰지 않는다.
 */
@Repository
public class AdminContentSearchRepository {

    /** 목록 내용 길이(문자 수). */
    public static final int CONTENT_PREVIEW = 200;

    private static final QBlog postBlog = new QBlog("postBlog");
    private static final QUser author = new QUser("author");

    private final JPAQueryFactory queryFactory;

    public AdminContentSearchRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 글 검색 조건. {@code titleQuery}는 BOOLEAN MODE 식(002 {@code SearchQuery#booleanQuery}), 나머지는 null이면 조건 없음. */
    public record PostCriteria(String titleQuery, String handle, Long authorId, PostStatus status,
            PostVisibility visibility) {
    }

    /** 댓글 검색 조건. {@code keyword}는 내용 부분 일치(서비스가 범위를 확인한 뒤에만 준다). */
    public record CommentCriteria(Long postId, Long authorId, String handle, CommentStatus status, String keyword) {
    }

    /** 방명록 검색 조건. */
    public record GuestbookCriteria(String handle, Long authorId, GuestbookStatus status, String keyword) {
    }

    public Page<AdminPostRow> posts(PostCriteria criteria, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder();
        if (criteria.titleQuery() != null) {
            where.and(MySqlFullTextFunctions.matchTitle(post.title, criteria.titleQuery()).gt(0.0));
        }
        if (criteria.handle() != null) {
            where.and(blog.handle.eq(criteria.handle()));
        }
        if (criteria.authorId() != null) {
            where.and(user.id.eq(criteria.authorId()));
        }
        if (criteria.status() != null) {
            where.and(post.status.eq(criteria.status()));
        }
        if (criteria.visibility() != null) {
            where.and(post.visibility.eq(criteria.visibility()));
        }
        List<Tuple> tuples = queryFactory
                .select(post.id, post.title, blog.handle, blog.title, blog.status, user.id, user.nickname, user.status,
                        post.status, post.visibility, post.publishedAt, post.createdAt, post.deletedAt,
                        post.commentCount)
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(where)
                .orderBy(post.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        List<AdminPostRow> rows = new ArrayList<>();
        for (Tuple t : tuples) {
            rows.add(new AdminPostRow(t.get(post.id), t.get(post.title),
                    new AdminPostRow.BlogRef(t.get(blog.handle), t.get(blog.title), t.get(blog.status)),
                    new ContentAuthor(t.get(user.id), t.get(user.nickname), t.get(user.status)), t.get(post.status),
                    t.get(post.visibility), t.get(post.publishedAt), t.get(post.createdAt), t.get(post.deletedAt),
                    t.get(post.commentCount)));
        }
        Long total = queryFactory.select(post.count())
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(where)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    public Page<AdminCommentRow> comments(CommentCriteria criteria, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder();
        if (criteria.postId() != null) {
            where.and(comment.post.id.eq(criteria.postId()));
        }
        if (criteria.authorId() != null) {
            where.and(comment.user.id.eq(criteria.authorId()));
        }
        if (criteria.handle() != null) {
            where.and(postBlog.handle.eq(criteria.handle()));
        }
        if (criteria.status() != null) {
            where.and(comment.status.eq(criteria.status()));
        }
        if (criteria.keyword() != null) {
            where.and(comment.content.contains(criteria.keyword()));
        }
        List<Tuple> tuples = queryFactory
                .select(comment.id, post.id, post.title, postBlog.handle, comment.parent.id, author.id,
                        author.nickname, author.status, comment.guestName, comment.secret, comment.content,
                        comment.status, comment.createdAt)
                .from(comment)
                .join(comment.post, post)
                .join(post.blog, postBlog)
                .leftJoin(comment.user, author)
                .where(where)
                .orderBy(comment.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        List<AdminCommentRow> rows = new ArrayList<>();
        for (Tuple t : tuples) {
            boolean secret = Boolean.TRUE.equals(t.get(comment.secret));
            rows.add(new AdminCommentRow(t.get(comment.id), t.get(post.id), t.get(post.title), t.get(postBlog.handle),
                    t.get(comment.parent.id), author(t), t.get(comment.guestName), secret,
                    secret ? null : preview(t.get(comment.content)), t.get(comment.status), t.get(comment.createdAt)));
        }
        Long total = queryFactory.select(comment.count())
                .from(comment)
                .join(comment.post, post)
                .join(post.blog, postBlog)
                .leftJoin(comment.user, author)
                .where(where)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    public Page<AdminGuestbookRow> guestbook(GuestbookCriteria criteria, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder();
        if (criteria.handle() != null) {
            where.and(blog.handle.eq(criteria.handle()));
        }
        if (criteria.authorId() != null) {
            where.and(guestbookEntry.user.id.eq(criteria.authorId()));
        }
        if (criteria.status() != null) {
            where.and(guestbookEntry.status.eq(criteria.status()));
        }
        if (criteria.keyword() != null) {
            where.and(guestbookEntry.content.contains(criteria.keyword()));
        }
        List<Tuple> tuples = queryFactory
                .select(guestbookEntry.id, blog.handle, guestbookEntry.parent.id, author.id, author.nickname,
                        author.status, guestbookEntry.guestName, guestbookEntry.secret, guestbookEntry.content,
                        guestbookEntry.status, guestbookEntry.createdAt)
                .from(guestbookEntry)
                .join(guestbookEntry.blog, blog)
                .leftJoin(guestbookEntry.user, author)
                .where(where)
                .orderBy(guestbookEntry.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        List<AdminGuestbookRow> rows = new ArrayList<>();
        for (Tuple t : tuples) {
            boolean secret = Boolean.TRUE.equals(t.get(guestbookEntry.secret));
            rows.add(new AdminGuestbookRow(t.get(guestbookEntry.id), t.get(blog.handle),
                    t.get(guestbookEntry.parent.id), author(t), t.get(guestbookEntry.guestName), secret,
                    secret ? null : preview(t.get(guestbookEntry.content)), t.get(guestbookEntry.status),
                    t.get(guestbookEntry.createdAt)));
        }
        Long total = queryFactory.select(guestbookEntry.count())
                .from(guestbookEntry)
                .join(guestbookEntry.blog, blog)
                .leftJoin(guestbookEntry.user, author)
                .where(where)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private static ContentAuthor author(Tuple t) {
        Long id = t.get(author.id);
        return id == null ? null : new ContentAuthor(id, t.get(author.nickname), t.get(author.status));
    }

    /** 앞 {@value #CONTENT_PREVIEW}자(문자 수, 서로게이트 쌍을 자르지 않는다). */
    static String preview(String content) {
        if (content == null || content.codePointCount(0, content.length()) <= CONTENT_PREVIEW) {
            return content;
        }
        return content.substring(0, content.offsetByCodePoints(0, CONTENT_PREVIEW));
    }
}
