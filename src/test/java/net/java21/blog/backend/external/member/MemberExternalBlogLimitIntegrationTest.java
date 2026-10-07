package net.java21.blog.backend.external.member;

import static net.java21.blog.backend.support.ExternalTestKit.rss;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.StubHttpServer;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 007 T025: 같은 회원이 동시에 신청해도 3개를 넘지 않고(회원 행 {@code FOR UPDATE}), 두 회원이 같은 피드를 동시에 신청하면 하나만 남는다
 * ({@code active_feed_hash} 유일). 실제 트랜잭션이 필요해 H2 {@code @SpringBootTest}로 띄운다. 피드는 루프백
 * {@link StubHttpServer}이므로 {@code blog.outbound.allow-private=true}와 그 포트를 허용한다(시험 전용).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:externallimit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.outbound.allow-private=true"
})
class MemberExternalBlogLimitIntegrationTest {

    private static final StubHttpServer SERVER = StubHttpServer.start();
    private static final String RSS = "application/rss+xml; charset=UTF-8";

    @Autowired
    private MemberExternalBlogService service;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private TransactionTemplate tx;

    @DynamicPropertySource
    static void outbound(DynamicPropertyRegistry registry) {
        registry.add("blog.outbound.allowed-ports", () -> "80,443," + SERVER.port());
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    private static JpaFixtures fixtures;

    private record Setup(long member, long other, long topic) {
    }

    private Setup setup(String prefix, int existing) {
        return tx.execute(status -> {
            if (fixtures == null) {
                fixtures = new JpaFixtures(em);
            }
            JpaFixtures f = fixtures;
            ExternalFixtures x = new ExternalFixtures(em);
            User member = f.user(prefix + "m");
            User other = f.user(prefix + "o");
            Topic topic = f.topic(f.topic(null, prefix + "-major", 1), prefix + "-minor", 1);
            for (int i = 0; i < existing; i++) {
                x.blog(member, "https://" + prefix + i + ".example/feed", topic, ExternalBlogStatus.ACTIVE);
            }
            return new Setup(member.getId(), other.getId(), topic.getId());
        });
    }

    private String feed(String name) {
        SERVER.respond("/" + name + ".xml", 200, RSS, rss("Blog " + name, SERVER.uri("/").toString(), "about",
                new String[] {"g1", "P1", SERVER.uri("/" + name + "/1").toString(), null, "body"}));
        return SERVER.uri("/" + name + ".xml").toString();
    }

    /** 두 작업을 동시에 시작해 결과(성공이면 null, 실패면 오류 코드)를 모은다. */
    private List<ErrorCode> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        task.call();
                        return null;
                    } catch (BusinessException e) {
                        return e.errorCode();
                    }
                }));
            }
            start.countDown();
            List<ErrorCode> results = new ArrayList<>();
            for (Future<ErrorCode> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsOfOneMemberStayWithinLimit() throws Exception {
        Setup s = setup("lim", 2);
        String c = feed("lim-c");
        String d = feed("lim-d");

        List<ErrorCode> results = race(() -> service.create(s.member(), c, s.topic(), null),
                () -> service.create(s.member(), d, s.topic(), null));

        assertThat(results).containsExactlyInAnyOrder(null, ErrorCode.EXTERNAL_BLOG_LIMIT_EXCEEDED);
        assertThat(blogRepository.countCounted(s.member())).isEqualTo(3);
    }

    @Test
    void concurrentRequestsForOneFeedKeepOne() throws Exception {
        Setup s = setup("dup", 0);
        String url = feed("dup-a");

        List<ErrorCode> results = race(() -> service.create(s.member(), url, s.topic(), null),
                () -> service.create(s.other(), url, s.topic(), null));

        assertThat(results).containsExactlyInAnyOrder(null, ErrorCode.EXTERNAL_BLOG_ALREADY_REGISTERED);
        assertThat(blogRepository.countCounted(s.member()) + blogRepository.countCounted(s.other())).isEqualTo(1);
    }
}
