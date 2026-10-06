package net.java21.blog.backend.subscription;

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
import java.util.concurrent.atomic.AtomicInteger;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.subscription.dto.SubscriptionStateResponse;
import net.java21.blog.backend.subscription.event.BlogSubscribedEvent;
import net.java21.blog.backend.subscription.repository.SubscriptionFeedQueryRepository;
import net.java21.blog.backend.subscription.service.SubscriptionService;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구독 동시성(T020, research D1): 같은 회원·블로그로 20번 동시에 구독하면 행 1, {@code subscriber_count} 1, 이벤트 1회.
 * 스레드마다 따로 커밋해야 하므로 테스트 트랜잭션을 쓰지 않고 끝에 지운다.
 */
@MySqlRepositoryTest
@Import({SubscriptionService.class, SubscriptionFeedQueryRepository.class, TagQueryRepository.class,
        SubscriptionConcurrencyTest.Events.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SubscriptionConcurrencyTest {

    private static final int REQUESTS = 20;

    @TestConfiguration(proxyBeanMethods = false)
    static class Events {
        @Bean
        SubscribedCounter subscribedCounter() {
            return new SubscribedCounter();
        }
    }

    static class SubscribedCounter {
        final AtomicInteger count = new AtomicInteger();

        @EventListener
        void on(BlogSubscribedEvent event) {
            count.incrementAndGet();
        }
    }

    @Autowired
    private SubscriptionService service;
    @Autowired
    private SubscribedCounter counter;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BlogRepository blogRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private Blog blog;

    @AfterEach
    void cleanUp() {
        if (blog != null) {
            jdbc.update("DELETE FROM blog_subscriptions WHERE blog_id = ?", blog.getId());
            jdbc.update("DELETE FROM blogs WHERE id = ?", blog.getId());
        }
        userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    void sameMemberConcurrentSubscribesCreateOneRowCountAndEvent() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User owner = user("owner" + tag);
        long reader = user("reader" + tag).getId();
        blog = blogRepository.save(new Blog(owner, "sb" + tag, "블로그"));
        counter.count.set(0);

        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SubscriptionStateResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.subscribe(reader, blog.getHandle());
                }));
            }
            start.countDown();
            for (Future<SubscriptionStateResponse> future : futures) {
                SubscriptionStateResponse response = future.get(60, TimeUnit.SECONDS);
                assertThat(response.subscribed()).isTrue();
                assertThat(response.subscriberCount()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_subscriptions WHERE blog_id = ?", Integer.class,
                blog.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subscriber_count FROM blogs WHERE id = ?", Integer.class,
                blog.getId())).isEqualTo(1);
        assertThat(counter.count.get()).isEqualTo(1);
    }

    private User user(String key) {
        User user = userRepository.save(new User(key + "@example.com", (key + "0".repeat(64)).substring(0, 64),
                "$2a$hash", "u", null, null, "2026-10-06", Instant.now()));
        userIds.add(user.getId());
        return user;
    }
}
