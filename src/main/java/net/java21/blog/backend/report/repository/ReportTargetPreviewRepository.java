package net.java21.blog.backend.report.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.comment.domain.QComment.comment;
import static net.java21.blog.backend.guestbook.domain.QGuestbookEntry.guestbookEntry;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.trackback.domain.QTrackback.trackback;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.dto.ReportTargetPreview.Author;
import net.java21.blog.backend.report.dto.ReportTargetPreview.BlogRef;
import net.java21.blog.backend.report.dto.ReportTargetPreview.State;
import net.java21.blog.backend.report.dto.TargetKey;
import net.java21.blog.backend.trackback.domain.TrackbackStatus;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.stereotype.Repository;

/**
 * 관리자 신고·숨김 화면의 대상 미리보기(005 research M4). 대상 종류마다 IN 쿼리 1회(최대 4회) — 대상 수와 관계없다. 글 본문은 주지 않고
 * PRIVATE·PROTECTED 글은 요약도 주지 않는다(006 FR-104). 댓글·방명록은 비밀 글도 내용 전체(신고 판단용, 결정 표 6번).
 * 주소는 front의 앵커 규칙({@code #comment-{id}}, {@code #guestbook-{id}})을 따른다.
 */
@Repository
public class ReportTargetPreviewRepository {

    private static final QUser author = new QUser("author");

    private final JPAQueryFactory queryFactory;
    private final SiteProperties site;

    public ReportTargetPreviewRepository(JPAQueryFactory queryFactory, SiteProperties site) {
        this.queryFactory = queryFactory;
        this.site = site;
    }

    /** 대상 하나(없으면 MISSING). */
    public ReportTargetPreview preview(ReportTargetType type, Long id) {
        TargetKey key = new TargetKey(type, id);
        return previews(List.of(key)).get(key);
    }

    /** 대상들의 미리보기. 결과에는 요청한 키가 모두 있다(없는 대상은 MISSING). 처리기가 없는 종류도 MISSING. */
    public Map<TargetKey, ReportTargetPreview> previews(Collection<TargetKey> keys) {
        Map<ReportTargetType, Set<Long>> byType = keys.stream().filter(k -> k.type() != null && k.id() != null)
                .collect(Collectors.groupingBy(TargetKey::type, Collectors.mapping(TargetKey::id, Collectors.toSet())));
        Map<TargetKey, ReportTargetPreview> found = new HashMap<>();
        byType.forEach((type, ids) -> {
            switch (type) {
                case POST -> posts(ids, found);
                case COMMENT -> comments(ids, found);
                case GUESTBOOK -> guestbook(ids, found);
                case TRACKBACK -> trackbacks(ids, found);
                default -> {
                    // 007 처리기가 없는 종류는 MISSING으로 둔다.
                }
            }
        });
        Map<TargetKey, ReportTargetPreview> result = new LinkedHashMap<>();
        for (TargetKey key : keys) {
            result.put(key, found.getOrDefault(key, ReportTargetPreview.missing(key.type(), key.id())));
        }
        return result;
    }

    private void posts(Set<Long> ids, Map<TargetKey, ReportTargetPreview> out) {
        List<Tuple> rows = queryFactory
                .select(post.id, post.title, post.summary, post.status, post.visibility, blog.handle, blog.title,
                        author.id, author.nickname)
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, author)
                .where(post.id.in(ids))
                .fetch();
        for (Tuple row : rows) {
            Long id = row.get(post.id);
            PostStatus status = row.get(post.status);
            String handle = row.get(blog.handle);
            State state = status == PostStatus.DELETED ? State.DELETED
                    : status == PostStatus.HIDDEN ? State.HIDDEN : State.ACTIVE;
            String text = row.get(post.visibility) == PostVisibility.PUBLIC ? row.get(post.summary) : null;
            out.put(new TargetKey(ReportTargetType.POST, id), new ReportTargetPreview(ReportTargetType.POST, id, state,
                    row.get(post.title), text, site.url("/" + handle + "/" + id),
                    new Author(row.get(author.id), row.get(author.nickname), false),
                    new BlogRef(handle, row.get(blog.title))));
        }
    }

    private void comments(Set<Long> ids, Map<TargetKey, ReportTargetPreview> out) {
        List<Tuple> rows = queryFactory
                .select(comment.id, comment.content, comment.status, comment.guestName, post.id, post.title,
                        blog.handle, blog.title, author.id, author.nickname)
                .from(comment)
                .join(comment.post, post)
                .join(post.blog, blog)
                .leftJoin(comment.user, author)
                .where(comment.id.in(ids))
                .fetch();
        for (Tuple row : rows) {
            Long id = row.get(comment.id);
            CommentStatus status = row.get(comment.status);
            String handle = row.get(blog.handle);
            State state = status == CommentStatus.DELETED ? State.DELETED
                    : status == CommentStatus.HIDDEN ? State.HIDDEN : State.ACTIVE;
            out.put(new TargetKey(ReportTargetType.COMMENT, id), new ReportTargetPreview(ReportTargetType.COMMENT, id,
                    state, row.get(post.title), state == State.DELETED ? null : row.get(comment.content),
                    site.url("/" + handle + "/" + row.get(post.id) + "#comment-" + id),
                    authorOf(row.get(author.id), row.get(author.nickname), row.get(comment.guestName)),
                    new BlogRef(handle, row.get(blog.title))));
        }
    }

    private void guestbook(Set<Long> ids, Map<TargetKey, ReportTargetPreview> out) {
        List<Tuple> rows = queryFactory
                .select(guestbookEntry.id, guestbookEntry.content, guestbookEntry.status, guestbookEntry.guestName,
                        blog.handle, blog.title, author.id, author.nickname)
                .from(guestbookEntry)
                .join(guestbookEntry.blog, blog)
                .leftJoin(guestbookEntry.user, author)
                .where(guestbookEntry.id.in(ids))
                .fetch();
        for (Tuple row : rows) {
            Long id = row.get(guestbookEntry.id);
            GuestbookStatus status = row.get(guestbookEntry.status);
            String handle = row.get(blog.handle);
            State state = status == GuestbookStatus.DELETED ? State.DELETED
                    : status == GuestbookStatus.HIDDEN ? State.HIDDEN : State.ACTIVE;
            out.put(new TargetKey(ReportTargetType.GUESTBOOK, id), new ReportTargetPreview(ReportTargetType.GUESTBOOK,
                    id, state, null, state == State.DELETED ? null : row.get(guestbookEntry.content),
                    site.url("/" + handle + "/guestbook#guestbook-" + id),
                    authorOf(row.get(author.id), row.get(author.nickname), row.get(guestbookEntry.guestName)),
                    new BlogRef(handle, row.get(blog.title))));
        }
    }

    private void trackbacks(Set<Long> ids, Map<TargetKey, ReportTargetPreview> out) {
        List<Tuple> rows = queryFactory
                .select(trackback.id, trackback.title, trackback.excerpt, trackback.sourceUrl, trackback.status,
                        trackback.blogName, blog.handle, blog.title)
                .from(trackback)
                .join(trackback.post, post)
                .join(post.blog, blog)
                .where(trackback.id.in(ids))
                .fetch();
        for (Tuple row : rows) {
            Long id = row.get(trackback.id);
            TrackbackStatus status = row.get(trackback.status);
            State state = status == TrackbackStatus.DELETED ? State.DELETED
                    : status == TrackbackStatus.HIDDEN ? State.HIDDEN : State.ACTIVE;
            out.put(new TargetKey(ReportTargetType.TRACKBACK, id), new ReportTargetPreview(ReportTargetType.TRACKBACK,
                    id, state, row.get(trackback.title), row.get(trackback.excerpt), row.get(trackback.sourceUrl),
                    null, new BlogRef(row.get(blog.handle), row.get(blog.title))));
        }
    }

    private static Author authorOf(Long userId, String nickname, String guestName) {
        return userId == null ? new Author(null, guestName, true) : new Author(userId, nickname, false);
    }
}
