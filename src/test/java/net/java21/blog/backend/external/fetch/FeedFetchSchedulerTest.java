package net.java21.blog.backend.external.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.repository.ExternalBlogQueryRepository;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.ExternalTestKit;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.topic.domain.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 007 T028: 수집 예약(research E1) — 선택·임대·제출, 대기열이 차면 임대를 둔 채 다음 차례로. */
@JpaRepositoryTest
@Import(ExternalBlogQueryRepository.class)
class FeedFetchSchedulerTest {

    private static final Instant NOW = JpaFixtures.T0.plusSeconds(60);

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogQueryRepository queryRepository;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private QueryCounter queryCounter;

    private ExternalFixtures x;
    private Topic topic;
    private FeedCollector collector;
    private final List<Runnable> queued = new ArrayList<>();

    @BeforeEach
    void setUp() {
        JpaFixtures f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
        collector = mock(FeedCollector.class);
    }

    private FeedFetchScheduler scheduler(TaskExecutor executor, String... props) {
        return new FeedFetchScheduler(queryRepository, collector, executor, ExternalTestKit.properties(props),
                new TransactionTemplate(transactionManager), new MutableClock(NOW));
    }

    private ExternalBlog due() {
        return x.blog(null, topic, ExternalBlogStatus.ACTIVE);
    }

    private Instant nextFetchAt(ExternalBlog blog) {
        em.clear();
        return blogRepository.findById(blog.getId()).orElseThrow().getNextFetchAt();
    }

    @Test
    void leasesDueBlogsAndSubmitsThem() {
        ExternalBlog a = due();
        ExternalBlog b = due();
        em.flush();

        int submitted = scheduler(queued::add, "lease-time", "10m").poll();

        assertThat(submitted).isEqualTo(2);
        assertThat(nextFetchAt(a)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(nextFetchAt(b)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        queued.forEach(Runnable::run);
        verify(collector).collect(a.getId());
        verify(collector).collect(b.getId());
        // 임대 중이면 다시 고르지 않는다
        assertThat(scheduler(queued::add).poll()).isZero();
    }

    @Test
    void respectsBatchSize() {
        due();
        due();
        due();
        em.flush();
        assertThat(scheduler(queued::add, "batch-size", "2").poll()).isEqualTo(2);
    }

    @Test
    void rejectedTaskKeepsLeaseAndSwallowsException() {
        ExternalBlog a = due();
        ExternalBlog b = due();
        em.flush();
        TaskExecutor full = task -> {
            throw new TaskRejectedException("full");
        };

        int submitted = scheduler(full).poll();

        assertThat(submitted).isZero();
        assertThat(nextFetchAt(a)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(nextFetchAt(b)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        verify(collector, never()).collect(anyLong());
    }

    @Test
    void collectorErrorsDoNotEscape() {
        ExternalBlog a = due();
        em.flush();
        doThrow(new IllegalStateException("boom")).when(collector).collect(a.getId());

        scheduler(Runnable::run).run();

        verify(collector).collect(a.getId());
    }

    @Test
    void nothingDueIsOneQuery() {
        x.blog(null, topic, ExternalBlogStatus.PAUSED);
        em.flush();
        em.clear();

        queryCounter.reset();
        assertThat(scheduler(queued::add).poll()).isZero();
        assertThat(queryCounter.count()).isEqualTo(1);
    }
}
