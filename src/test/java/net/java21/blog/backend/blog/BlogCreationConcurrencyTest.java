package net.java21.blog.backend.blog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.java21.blog.backend.auth.PasswordConfig;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.dto.CreateBlogRequest;
import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.blog.service.BlogService;
import net.java21.blog.backend.category.repository.CategoryQueryRepository;
import net.java21.blog.backend.blog.service.HandlePolicy;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 2개인 회원이 서로 다른 주소로 동시에 10번 만들면 1건만 성공한다(T057, quickstart #31, R28).
 * 회원 행 {@code SELECT ... FOR UPDATE}가 실제 InnoDB 잠금으로 요청을 줄 세우는지 테스트 MySQL에서 확인한다.
 * 스레드마다 따로 커밋해야 하므로 테스트 트랜잭션을 쓰지 않고, 만든 행은 끝에 지운다.
 */
@MySqlRepositoryTest
@Import({BlogCreationConcurrencyTest.Services.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BlogCreationConcurrencyTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BlogsProperties.class)
    @Import({BlogQueryRepository.class, CategoryQueryRepository.class, BlogService.class, BlogAccess.class, HandlePolicy.class, PasswordConfig.class})
    static class Services {
    }

    private static final int REQUESTS = 10;

    @Autowired
    private BlogService blogService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BlogRepository blogRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private User user;

    @AfterEach
    void cleanUp() {
        if (user != null) {
            jdbc.update("DELETE FROM blogs WHERE user_id = ?", user.getId());
            jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
        }
    }

    @Test
    void onlyOneOfTenConcurrentCreatesSucceeds() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        user = userRepository.save(new User("race-" + tag + "@example.com", (tag + "0".repeat(64)).substring(0, 64),
                "$2a$hash", "race", null, null, "2026-10-06", Instant.now()));
        blogRepository.save(new Blog(user, "r" + tag + "-a", "a"));
        blogRepository.save(new Blog(user, "r" + tag + "-b", "b"));

        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ErrorCode>> results = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS; i++) {
                String handle = "r" + tag + "-c" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        blogService.create(user.getId(), new CreateBlogRequest(handle, null));
                        return null;
                    } catch (BusinessException e) {
                        return e.errorCode();
                    }
                }));
            }
            start.countDown();
            List<ErrorCode> outcomes = new ArrayList<>();
            for (Future<ErrorCode> result : results) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(code -> code == null).hasSize(1);
            assertThat(outcomes).filteredOn(code -> code != null).hasSize(REQUESTS - 1)
                    .containsOnly(ErrorCode.BLOG_LIMIT_EXCEEDED);
            assertThat(blogRepository.countByUserIdAndStatus(user.getId(), BlogStatus.ACTIVE)).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }
}
