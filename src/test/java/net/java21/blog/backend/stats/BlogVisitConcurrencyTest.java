package net.java21.blog.backend.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.stats.repository.BlogVisitRepository;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 방문 기록 동시성(T056, MySQL): 같은 블로그·같은 날 upsert + 전체 수 증가 20회 동시 → 행 1, visitors 20, total 20. */
@MySqlRepositoryTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BlogVisitConcurrencyTest {

    private static final int REQUESTS = 20;
    private static final LocalDate TODAY = LocalDate.parse("2026-10-07");

    @Autowired
    private BlogVisitRepository repository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BlogRepository blogRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(new User("bv" + tag + "@example.com", ("bv" + tag + "0".repeat(64)).substring(0, 64),
                "$2a$hash", "bv", null, null, "2026-10-07", Instant.now()));
        blog = blogRepository.save(new Blog(owner, "bv" + tag, "블로그"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM blog_daily_visits WHERE blog_id = ?", blog.getId());
        jdbc.update("DELETE FROM blogs WHERE id = ?", blog.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", owner.getId());
    }

    @Test
    void concurrentVisitsOfTheSameDayKeepOneRowAndExactCounts() throws Exception {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return transactionTemplate.execute(status -> {
                        // VisitService와 같은 순서: 블로그 행 배타 잠금 → 일별 행 upsert(반대 순서는 교착 상태)
                        int incremented = repository.incrementTotal(blog.getId());
                        return incremented + repository.upsertVisit(blog.getId(), TODAY, Instant.now());
                    });
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS)).isPositive();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_daily_visits WHERE blog_id = ?", Integer.class,
                blog.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT visitors FROM blog_daily_visits WHERE blog_id = ?", Integer.class,
                blog.getId())).isEqualTo(REQUESTS);
        assertThat(jdbc.queryForObject("SELECT total_visitors FROM blogs WHERE id = ?", Long.class, blog.getId()))
                .isEqualTo(REQUESTS);
    }
}
