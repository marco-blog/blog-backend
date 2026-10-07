package net.java21.blog.backend.trackback.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.QBlog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.domain.QPost;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.trackback.TrackbackUrls;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.domain.TrackbackStatus;
import net.java21.blog.backend.user.domain.QUser;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** 트랙백 목록 쿼리(005 T087, FR-051, research M14): 노출 조건, 고정 쿼리 수, 별칭 노출 조각. */
@JpaRepositoryTest
@Import(TrackbackQueryRepository.class)
class TrackbackQueryRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private TrackbackQueryRepository repository;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private Blog mine;
    private Post target;
    private Post otherTarget;
    private Blog other;
    private User otherOwner;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        mine = fx.blog(fx.user("tbowner"), "tbowner");
        target = fx.published(mine, "받는 글", null, 0);
        otherTarget = fx.published(mine, "다른 받는 글", null, 1);
        otherOwner = fx.user("tbsender");
        other = fx.blog(otherOwner, "tbsender");
    }

    private Trackback trackback(Post to, Post source, String url) {
        Trackback t = new Trackback(to, source, url, TrackbackUrls.hash(url), "제목 " + url, "요약", "블로그", "1.2.3.4");
        em.persist(t);
        return t;
    }

    @Test
    void publicListShowsActiveExternalAndVisibleInternalTrackbacksNewestFirstInTwoQueries() {
        Trackback external = trackback(target, null, "https://ext.example/1");
        Trackback visibleInternal = trackback(target, fx.published(other, "공개 출처", null, 2),
                "https://blog.java21.net/tbsender/1");
        trackback(target, fx.post(other, "비공개 출처", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 3),
                "https://blog.java21.net/tbsender/2");
        trackback(target, fx.post(other, "보호 출처", null, PostStatus.PUBLISHED, PostVisibility.PROTECTED, 4),
                "https://blog.java21.net/tbsender/3");
        trackback(target, fx.post(other, "삭제 출처", null, PostStatus.DELETED, PostVisibility.PUBLIC, 5),
                "https://blog.java21.net/tbsender/4");
        Post hiddenSource = fx.published(other, "숨긴 출처", null, 6);
        hiddenSource.hide();
        trackback(target, hiddenSource, "https://blog.java21.net/tbsender/5");
        Trackback deleted = trackback(target, null, "https://ext.example/deleted");
        deleted.markDeleted();
        Trackback hidden = trackback(target, null, "https://ext.example/hidden");
        hidden.hide();
        trackback(otherTarget, null, "https://ext.example/other-post");
        Trackback newest = trackback(target, null, "https://ext.example/newest");
        fx.flushAndClear();

        queryCounter.reset();
        Page<TrackbackRow> page = repository.findVisible(target.getId(), PageRequest.of(0, 20));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(TrackbackRow::id)
                .containsExactly(newest.getId(), visibleInternal.getId(), external.getId());
        TrackbackRow internal = page.getContent().get(1);
        assertThat(internal.sourcePostId()).isNotNull();
        assertThat(internal.url()).isEqualTo("https://blog.java21.net/tbsender/1");
        assertThat(internal.postTitle()).isEqualTo("받는 글");
        assertThat(page.getContent().get(0).sourcePostId()).isNull();

        queryCounter.reset();
        assertThat(repository.countVisible(target.getId())).isEqualTo(3);
        assertThat(queryCounter.count()).isEqualTo(1);
    }

    @Test
    void sourceFromSuspendedAuthorDisappears() {
        Trackback internal = trackback(target, fx.published(other, "정지 회원 글", null, 2),
                "https://blog.java21.net/tbsender/9");
        otherOwner.suspend();
        fx.flushAndClear();

        assertThat(repository.findVisible(target.getId(), PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThat(repository.countVisible(target.getId())).isZero();
        assertThat(internal.getId()).isNotNull();
    }

    @Test
    void pagingKeepsTotalCount() {
        for (int i = 0; i < 25; i++) {
            trackback(target, null, "https://ext.example/p" + i);
        }
        fx.flushAndClear();

        Page<TrackbackRow> second = repository.findVisible(target.getId(), PageRequest.of(1, 10));
        assertThat(second.getContent()).hasSize(10);
        assertThat(second.getTotalElements()).isEqualTo(25);
    }

    @Test
    void manageListShowsActiveAndHiddenOfTheBlogsPostsWithReceivingPostTitleInTwoQueries() {
        Trackback active = trackback(target, null, "https://ext.example/a");
        Trackback hidden = trackback(otherTarget, fx.post(other, "비공개 출처", null, PostStatus.PUBLISHED,
                PostVisibility.PRIVATE, 3), "https://blog.java21.net/tbsender/2");
        hidden.hide();
        Trackback deleted = trackback(target, null, "https://ext.example/d");
        deleted.markDeleted();
        Post foreign = fx.published(other, "남의 글", null, 4);
        trackback(foreign, null, "https://ext.example/foreign");
        fx.flushAndClear();

        queryCounter.reset();
        Page<TrackbackRow> page = repository.findManaged(mine.getId(), PageRequest.of(0, 20));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(TrackbackRow::id).containsExactly(hidden.getId(), active.getId());
        assertThat(page.getContent().get(0).status()).isEqualTo(TrackbackStatus.HIDDEN);
        assertThat(page.getContent().get(0).postTitle()).isEqualTo("다른 받는 글");
        assertThat(page.getContent().get(0).postId()).isEqualTo(otherTarget.getId());
        assertThat(page.getContent().get(0).sourcePostId()).as("관리 목록은 출처 노출과 무관").isNotNull();
        assertThat(page.getContent().get(1).postTitle()).isEqualTo("받는 글");
    }

    @Test
    void recentPingsNewestFirstLimitedInOneQuery() {
        for (int i = 0; i < 55; i++) {
            em.persist(new TrackbackPingLog(target, "https://ext.example/tb/" + i));
        }
        em.persist(new TrackbackPingLog(otherTarget, "https://ext.example/other"));
        fx.flushAndClear();

        queryCounter.reset();
        List<TrackbackPingLog> logs = repository.findRecentPings(target.getId(), 50);

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(logs).hasSize(50);
        assertThat(logs.get(0).getTargetUrl()).isEqualTo("https://ext.example/tb/54");
        assertThat(logs).extracting(TrackbackPingLog::getTargetUrl).doesNotContain("https://ext.example/other");
    }

    @Test
    void aliasedBodyVisibleFragmentMatchesTheDefaultOne() {
        fx.published(other, "공개", null, 1);
        fx.post(other, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 2);
        fx.post(other, "보호", null, PostStatus.PUBLISHED, PostVisibility.PROTECTED, 3);
        fx.post(other, "임시", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 4);
        fx.post(other, "삭제", null, PostStatus.DELETED, PostVisibility.PUBLIC, 5);
        fx.flushAndClear();

        List<Long> byDefault = queryFactory.select(post.id).from(post).join(post.blog, blog).join(blog.user, user)
                .where(PostExposure.bodyVisible()).orderBy(post.id.asc()).fetch();
        QPost p = new QPost("p");
        QBlog b = new QBlog("b");
        QUser u = new QUser("u");
        List<Long> byAlias = queryFactory.select(p.id).from(p).join(p.blog, b).join(b.user, u)
                .where(PostExposure.bodyVisible(p, b, u)).orderBy(p.id.asc()).fetch();
        List<Long> listableDefault = queryFactory.select(post.id).from(post).join(post.blog, blog)
                .join(blog.user, user).where(PostExposure.listable()).orderBy(post.id.asc()).fetch();
        List<Long> listableAlias = queryFactory.select(p.id).from(p).join(p.blog, b).join(b.user, u)
                .where(PostExposure.listable(p, b, u)).orderBy(p.id.asc()).fetch();

        assertThat(byAlias).isEqualTo(byDefault).hasSize(3);
        assertThat(listableAlias).isEqualTo(listableDefault).hasSize(4);
    }
}
