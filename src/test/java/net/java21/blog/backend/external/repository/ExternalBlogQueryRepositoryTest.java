package net.java21.blog.backend.external.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** 007 T027: 수집 선택·임대, 회원별 세는 수, 관리자·회원 목록(쿼리 수 고정, N+1 없음). */
@JpaRepositoryTest
@Import(ExternalBlogQueryRepository.class)
class ExternalBlogQueryRepositoryTest {

    private static final Instant T0 = JpaFixtures.T0;

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogQueryRepository repository;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures f;
    private ExternalFixtures x;
    private User member;
    private Topic topic;

    @BeforeEach
    void setUp() {
        f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        member = f.user("member");
        topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
    }

    private void nextFetchAt(ExternalBlog blog, Instant at) {
        em.flush();
        em.createQuery("update ExternalBlog b set b.nextFetchAt = :at where b.id = :id").setParameter("at", at)
                .setParameter("id", blog.getId()).executeUpdate();
    }

    @Test
    void picksDueActiveBlogsOldestFirstWithLimit() {
        ExternalBlog late = x.blog(null, topic, ExternalBlogStatus.ACTIVE);
        ExternalBlog early = x.blog(null, topic, ExternalBlogStatus.ACTIVE);
        ExternalBlog third = x.blog(null, topic, ExternalBlogStatus.ACTIVE);
        ExternalBlog future = x.blog(null, topic, ExternalBlogStatus.ACTIVE);
        ExternalBlog paused = x.blog(null, topic, ExternalBlogStatus.ACTIVE);
        nextFetchAt(late, T0.minusSeconds(10));
        nextFetchAt(early, T0.minusSeconds(60));
        nextFetchAt(third, T0);
        nextFetchAt(future, T0.plusSeconds(1));
        ExternalFixtures.moveTo(em.find(ExternalBlog.class, paused.getId()), ExternalBlogStatus.PAUSED);
        x.blog(member, topic, ExternalBlogStatus.PENDING);
        em.flush();
        em.clear();

        queryCounter.reset();
        List<Long> due = repository.findDueIds(T0, 2);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(due).containsExactly(early.getId(), late.getId());
        assertThat(repository.findDueIds(T0, 10)).containsExactly(early.getId(), late.getId(), third.getId());
        assertThat(repository.findDueIds(T0.minus(Duration.ofDays(1)), 10)).isEmpty();
    }

    @Test
    void leaseMovesNextFetchOnceAndSkipsInactive() {
        ExternalBlog a = x.blog(null, topic, ExternalBlogStatus.ACTIVE);
        ExternalBlog b = x.blog(null, topic, ExternalBlogStatus.ACTIVE);
        ExternalBlog stopped = x.blog(null, topic, ExternalBlogStatus.STOPPED);
        em.flush();
        em.clear();
        Instant until = T0.plus(Duration.ofMinutes(15));

        queryCounter.reset();
        long updated = repository.lease(List.of(a.getId(), b.getId(), stopped.getId()), until);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(updated).isEqualTo(2);
        em.clear();
        assertThat(blogRepository.findById(a.getId()).orElseThrow().getNextFetchAt()).isEqualTo(until);
        assertThat(blogRepository.findById(stopped.getId()).orElseThrow().getNextFetchAt()).isNull();
        assertThat(repository.findDueIds(T0, 10)).isEmpty();
        assertThat(repository.lease(List.of(), until)).isZero();
    }

    @Test
    void countsHeldRegistrationsPerMember() {
        x.blog(member, topic, ExternalBlogStatus.PENDING);
        x.blog(member, topic, ExternalBlogStatus.ACTIVE);
        x.blog(member, topic, ExternalBlogStatus.BLOCKED);
        x.blog(member, topic, ExternalBlogStatus.PAUSED);
        x.blog(member, topic, ExternalBlogStatus.STOPPED);
        x.blog(member, topic, ExternalBlogStatus.REJECTED);
        x.blog(member, topic, ExternalBlogStatus.RELEASED);
        x.blog(f.user("other"), topic, ExternalBlogStatus.ACTIVE);
        em.flush();

        assertThat(blogRepository.countCounted(member.getId())).isEqualTo(5);
    }

    @Test
    void adminListPutsPendingFirstAndCountsInTwoQueries() {
        User reviewerUser = f.user("reviewer");
        ExternalBlog active = x.blog(member, "https://alpha.example/feed", topic, ExternalBlogStatus.ACTIVE);
        ExternalBlog pending = x.blog(member, "https://beta.example/feed", topic, ExternalBlogStatus.PENDING);
        ExternalBlog direct = ExternalBlog.adminDirect(reviewerUser, "basis", "https://gamma.example/feed",
                net.java21.blog.backend.external.feed.FeedUrlNormalizer.hash("https://gamma.example/feed"), topic, T0);
        direct.describe("Gamma Notes", "https://gamma.example/", null);
        em.persist(direct);
        ExternalPost p1 = x.post(active, "one", topic, null);
        ExternalPost p2 = x.post(active, "two", topic, null);
        x.removed(active, "gone", topic, RemovedReason.ADMIN);
        x.review(p1, topic, 0.4);
        x.review(p2, topic, 0.3);
        for (int i = 0; i < 4; i++) {
            x.blog(f.user("m" + i), topic, ExternalBlogStatus.ACTIVE);
        }
        em.flush();
        em.clear();

        queryCounter.reset();
        Page<ExternalBlogQueryRepository.BlogRow> page = repository.findAdminBlogs(null, null, PageRequest.of(0, 20));
        page.getContent().forEach(r -> {
            if (r.blog().getMember() != null) {
                r.blog().getMember().getNickname();
            }
            if (r.blog().getReviewedBy() != null) {
                r.blog().getReviewedBy().getNickname();
            }
        });
        assertThat(queryCounter.count()).isLessThanOrEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(7);
        assertThat(page.getContent().getFirst().blog().getId()).isEqualTo(pending.getId());
        ExternalBlogQueryRepository.BlogRow alpha = page.getContent().stream()
                .filter(r -> r.blog().getId().equals(active.getId())).findFirst().orElseThrow();
        assertThat(alpha.postCount()).isEqualTo(2);
        assertThat(alpha.pendingReviewCount()).isEqualTo(2);
        assertThat(alpha.blog().getMember().getNickname()).isEqualTo("member");

        assertThat(repository.findAdminBlogs(ExternalBlogStatus.PENDING, null, PageRequest.of(0, 20)).getContent())
                .extracting(r -> r.blog().getId()).containsExactly(pending.getId());
        assertThat(repository.findAdminBlogs(null, "GAMMA notes", PageRequest.of(0, 20)).getContent())
                .extracting(r -> r.blog().getId()).containsExactly(direct.getId());
        assertThat(repository.findAdminBlogs(null, "alpha.example", PageRequest.of(0, 20)).getContent())
                .extracting(r -> r.blog().getId()).containsExactly(active.getId());
        Page<ExternalBlogQueryRepository.BlogRow> second = repository.findAdminBlogs(ExternalBlogStatus.ACTIVE, null,
                PageRequest.of(1, 2));
        assertThat(second.getTotalElements()).isEqualTo(6);
        assertThat(second.getContent()).hasSize(2);
    }

    @Test
    void findRowFetchesMemberAndReviewerInOneQuery() {
        ExternalBlog active = x.blog(member, topic, ExternalBlogStatus.ACTIVE);
        x.post(active, "one", topic, null);
        em.flush();
        em.clear();

        queryCounter.reset();
        ExternalBlogQueryRepository.BlogRow row = repository.findRow(active.getId());
        row.blog().getMember().getNickname();
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(row.postCount()).isEqualTo(1);
        assertThat(repository.findRow(999_999L)).isNull();
    }

    @Test
    void memberListIsRecentTwentyWithPostCountInOneQuery() {
        User other = f.user("other");
        ExternalBlog first = null;
        for (int i = 0; i < 22; i++) {
            ExternalBlog b = x.blog(member, topic, i % 2 == 0 ? ExternalBlogStatus.REJECTED
                    : ExternalBlogStatus.RELEASED);
            if (i == 0) {
                first = b;
            }
        }
        ExternalBlog latest = x.blog(member, topic, ExternalBlogStatus.ACTIVE);
        x.post(latest, "one", topic, null);
        x.post(latest, "two", topic, null);
        x.blog(other, topic, ExternalBlogStatus.ACTIVE);
        em.flush();
        em.clear();

        queryCounter.reset();
        List<ExternalBlogQueryRepository.BlogRow> rows = repository.findMemberBlogs(member.getId());
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(rows).hasSize(ExternalBlogQueryRepository.MEMBER_LIST_MAX);
        assertThat(rows.getFirst().blog().getId()).isEqualTo(latest.getId());
        assertThat(rows.getFirst().postCount()).isEqualTo(2);
        Long firstId = first.getId();
        assertThat(rows).noneMatch(r -> r.blog().getId().equals(firstId));
        assertThat(rows).allMatch(r -> r.blog().getMember().getId().equals(member.getId()));
    }
}
