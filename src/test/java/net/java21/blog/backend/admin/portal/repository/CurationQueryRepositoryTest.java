package net.java21.blog.backend.admin.portal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.portal.CurationStatus;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.portal.domain.PortalCuration;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 관리자 포털 조회(003 T083): 상태별 추천 목록과 정렬·관리자 닉네임, 기간이 겹치는 추천 수(경계 {@code [starts, ends)}, 자기 제외),
 * 노출 가능 글 id, 제외 목록. 목록은 쿼리 2회(목록 + 수)로 고정이다.
 */
@JpaRepositoryTest
@Import(CurationQueryRepository.class)
class CurationQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final String TEXT = "가".repeat(200);

    @Autowired
    private EntityManager em;
    @Autowired
    private CurationQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private User admin;
    private Blog blog;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        admin = fx.user("관리자");
        blog = fx.blog(fx.user("marco"), "marco");
    }

    @Test
    void curationsByStatusAreOrderedAndCarryPostAndAdmin() {
        Post a = post("a");
        Post b = post("b");
        Post c = post("c");
        Post d = post("d");
        PortalCuration active2 = curation(a, NOW.minusSeconds(10), NOW.plusSeconds(10), 2);
        PortalCuration active1 = curation(b, NOW, NOW.plusSeconds(10), 1);
        PortalCuration upcoming = curation(c, NOW.plusSeconds(1), NOW.plusSeconds(10), 0);
        PortalCuration ended = curation(d, NOW.minusSeconds(10), NOW, 0);
        PortalCuration endedEarlier = curation(d, NOW.minusSeconds(20), NOW.minusSeconds(15), 0);
        fx.flushAndClear();

        queryCounter.reset();
        Page<CurationRow> activePage = repository.findCurations(CurationStatus.ACTIVE, NOW, PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(activePage.getContent()).extracting(CurationRow::id)
                .containsExactly(active2.getId(), active1.getId());
        assertThat(activePage.getTotalElements()).isEqualTo(2);
        CurationRow first = activePage.getContent().get(0);
        assertThat(first.postId()).isEqualTo(a.getId());
        assertThat(first.postTitle()).isEqualTo("a");
        assertThat(first.blogHandle()).isEqualTo("marco");
        assertThat(first.createdById()).isEqualTo(admin.getId());
        assertThat(first.createdByNickname()).isEqualTo("관리자");
        assertThat(first.createdAt()).isNotNull();

        assertThat(repository.findCurations(CurationStatus.UPCOMING, NOW, PageRequest.of(0, 20)).getContent())
                .extracting(CurationRow::id).containsExactly(upcoming.getId());
        assertThat(repository.findCurations(CurationStatus.ENDED, NOW, PageRequest.of(0, 20)).getContent())
                .extracting(CurationRow::id).containsExactly(ended.getId(), endedEarlier.getId());
        Page<CurationRow> all = repository.findCurations(null, NOW, PageRequest.of(0, 2));
        assertThat(all.getTotalElements()).isEqualTo(5);
        assertThat(all.getContent()).hasSize(2);
        assertThat(repository.findCuration(upcoming.getId()).postId()).isEqualTo(c.getId());
    }

    @Test
    void overlappingCountUsesHalfOpenPeriodAndCanSkipItself() {
        Post a = post("a");
        PortalCuration inside = curation(a, NOW, NOW.plusSeconds(100), 0);
        curation(a, NOW.minusSeconds(100), NOW, 0);
        curation(a, NOW.plusSeconds(100), NOW.plusSeconds(200), 0);
        curation(a, NOW.plusSeconds(50), NOW.plusSeconds(150), 0);
        fx.flushAndClear();

        queryCounter.reset();
        assertThat(repository.countOverlapping(NOW, NOW.plusSeconds(100), null)).isEqualTo(2);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.countOverlapping(NOW, NOW.plusSeconds(100), inside.getId())).isEqualTo(1);
        assertThat(repository.countOverlapping(NOW.minusSeconds(1), NOW.plusSeconds(101), null)).isEqualTo(4);
    }

    @Test
    void portalVisiblePostIdsAreFilteredInOneQuery() {
        Post ok = post("ok");
        Post privatePost = fx.publishedText(blog, "private", TEXT, null, PostStatus.PUBLISHED, PostVisibility.PRIVATE,
                NOW.minusSeconds(60));
        Post excluded = post("excluded");
        em.persist(new PortalExclusion(excluded, "광고", admin));
        fx.joinedAt(blog.getUser(), NOW.minus(Duration.ofDays(2)));
        fx.flushAndClear();

        queryCounter.reset();
        Set<Long> visible = repository.findPortalVisiblePostIds(
                List.of(ok.getId(), privatePost.getId(), excluded.getId()),
                new PortalCriteria(NOW, Duration.ofHours(24), 200));
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(visible).containsExactly(ok.getId());
        assertThat(repository.findPortalVisiblePostIds(List.of(), new PortalCriteria(NOW, Duration.ZERO, 0))).isEmpty();
    }

    @Test
    void exclusionsAreNewestFirstWithPostAndAdmin() {
        Post a = post("a");
        Post b = post("b");
        em.persist(new PortalExclusion(a, "광고", admin));
        em.flush();
        em.persist(new PortalExclusion(b, "도배", admin));
        fx.flushAndClear();
        em.createNativeQuery("UPDATE portal_exclusions SET created_at = :t WHERE post_id = :id")
                .setParameter("t", NOW.minusSeconds(60)).setParameter("id", a.getId()).executeUpdate();

        queryCounter.reset();
        Page<ExclusionRow> page = repository.findExclusions(PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getContent()).extracting(ExclusionRow::postId).containsExactly(b.getId(), a.getId());
        assertThat(page.getContent().get(0).reason()).isEqualTo("도배");
        assertThat(page.getContent().get(0).blogHandle()).isEqualTo("marco");
        assertThat(page.getContent().get(0).excludedByNickname()).isEqualTo("관리자");
        assertThat(repository.findExclusion(a.getId()).reason()).isEqualTo("광고");
        assertThat(repository.findExclusion(999L)).isNull();
    }

    private Post post(String title) {
        return fx.publishedText(blog, title, TEXT, null, NOW.minusSeconds(60));
    }

    private PortalCuration curation(Post post, Instant starts, Instant ends, int sortOrder) {
        PortalCuration curation = new PortalCuration(post, starts, ends, sortOrder, admin);
        em.persist(curation);
        return curation;
    }
}
