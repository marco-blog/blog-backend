package net.java21.blog.backend.post;

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
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostDailyStatsRepository;
import net.java21.blog.backend.post.repository.PostRepository;
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

/**
 * 일별 통계 동시 upsert(003 T027, research P4): 같은 글·같은 날 {@code upsertView} 20회를 동시에 실행하면 행 1, views 20.
 * 스레드마다 따로 커밋해야 하므로 테스트 트랜잭션을 쓰지 않고 끝에 지운다.
 */
@MySqlRepositoryTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostDailyStatsConcurrencyTest {

    private static final int REQUESTS = 20;
    private static final LocalDate TODAY = LocalDate.parse("2026-10-06");

    @Autowired
    private PostDailyStatsRepository repository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BlogRepository blogRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private User owner;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(new User("ds" + tag + "@example.com", ("ds" + tag + "0".repeat(64)).substring(0, 64),
                "$2a$hash", "ds", null, null, "2026-10-06", Instant.now()));
        blog = blogRepository.save(new Blog(owner, "ds" + tag, "블로그"));
        Post p = new Post(blog, "글");
        p.publish("글", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, Instant.now());
        post = postRepository.save(p);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM post_daily_stats WHERE post_id = ?", post.getId());
        jdbc.update("DELETE FROM posts WHERE id = ?", post.getId());
        jdbc.update("DELETE FROM blogs WHERE id = ?", blog.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", owner.getId());
    }

    @Test
    void concurrentUpsertsOfTheSameDayKeepOneRowAndExactCount() throws Exception {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return transactionTemplate.execute(status -> repository.upsertView(post.getId(), TODAY,
                            Instant.now()));
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS)).isPositive();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_daily_stats WHERE post_id = ?", Integer.class,
                post.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT views FROM post_daily_stats WHERE post_id = ?", Integer.class,
                post.getId())).isEqualTo(REQUESTS);
    }
}
