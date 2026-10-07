package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.java21.blog.backend.support.ExternalTestKit;
import net.java21.blog.backend.support.StubHttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 007 T090: 핵심 흐름 하나를 실제 스케줄러로(H2 {@code @SpringBootTest}, 루프백 {@link StubHttpServer}, 시험 전용
 * {@code blog.outbound.allow-private=true}, 수집 주기 1초). 회원 신청 → 관리자 승인 → 스케줄러가 수집 → {@code GET /portal}에 외부 카드
 * → visit 302·클릭 1 → 회원 신고 접수(005 처리기에 {@code EXTERNAL_POST}가 붙음) → "남기기"로 해제 → 포털에 그대로·피드를 더 읽지 않음
 * → 남긴 글 삭제 → 포털에서 사라짐.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:externalflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "blog.external.poll-interval=300ms",
        "blog.external.fetch-interval=PT1S",
        "blog.external.fetch-jitter=PT0S"
})
class ExternalFeedFlowIntegrationTest extends AdminConsoleIntegrationSupport {

    private static final StubHttpServer FEEDS = StubHttpServer.start();
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final Duration WAIT = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void feeds(DynamicPropertyRegistry registry) {
        registry.add("blog.outbound.allow-private", () -> "true");
        registry.add("blog.outbound.allowed-ports", () -> "80,443," + FEEDS.port());
    }

    @AfterAll
    static void stopFeeds() {
        FEEDS.close();
    }

    private static void serveFeed(String[]... items) {
        FEEDS.respond("/flow.xml", 200, "application/rss+xml; charset=UTF-8",
                ExternalTestKit.rss("Flow Blog", FEEDS.uri("/").toString(), "flow", items));
    }

    private static String[] item(String guid, String title) {
        return new String[] {guid, title, FEEDS.uri("/posts/" + guid).toString(),
                RFC_1123.format(Instant.now().minus(Duration.ofHours(1))), "<p>요약 " + title + "</p>"};
    }

    private static long feedReads() {
        return FEEDS.requests().stream().filter(r -> r.path().equals("/flow.xml")).count();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("기다림: " + what).isBefore(deadline);
            Thread.sleep(200);
        }
    }

    private String portal() {
        try {
            Reply reply = send(HttpMethod.GET, "/api/v1/portal/latest", null, null);
            assertThat(reply.status()).isEqualTo(200);
            return reply.body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void memberRequestToPortalVisitReleaseKeepAndDeleteKept() throws Exception {
        serveFeed(item("g1", "Flow first"));
        Member owner = signup("flowown");
        Member reader = signup("flowread");
        Member admin = signupAs("flowadm", "SUPER_ADMIN");
        long topicId = jdbc.queryForObject("SELECT MIN(id) FROM topics WHERE parent_id IS NOT NULL", Long.class);

        Reply requested = send(HttpMethod.POST, "/api/v1/me/external-blogs", "{\"feedUrl\":\"%s\",\"defaultTopicId\":%d}"
                .formatted(FEEDS.uri("/flow.xml"), topicId), owner.cookie());
        assertThat(requested.status()).as(requested.body()).isEqualTo(201);
        long blogId = ((Number) requested.read("$.result.id")).longValue();
        assertThat(send(HttpMethod.POST, "/api/v1/admin/external-blogs/" + blogId + "/approve", null,
                admin.cookie()).status()).isEqualTo(200);

        await("스케줄러가 첫 글을 수집해 포털에 카드", () -> portal().contains("Flow first"));
        String latest = portal();
        assertThat(latest).contains("\"source\":\"EXTERNAL\"");
        long postId = jdbc.queryForObject("SELECT id FROM external_posts WHERE external_blog_id = ?", Long.class,
                blogId);

        var visit = mvc.perform(json(HttpMethod.GET, "/api/v1/external-posts/" + postId + "/visit", null,
                reader.cookie())).andReturn().getResponse();
        assertThat(visit.getStatus()).isEqualTo(302);
        assertThat(visit.getHeader("Location")).isEqualTo(FEEDS.uri("/posts/g1").toString());
        assertThat(visit.getHeader("Cache-Control")).contains("no-store");
        Reply posts = send(HttpMethod.GET, "/api/v1/me/external-blogs/" + blogId + "/posts", null, owner.cookie());
        assertThat(((Number) posts.read("$.result[0].clickCount")).intValue()).isEqualTo(1);

        Reply reported = send(HttpMethod.POST, "/api/v1/reports",
                "{\"targetType\":\"EXTERNAL_POST\",\"targetId\":%d,\"reason\":\"SPAM\"}".formatted(postId),
                reader.cookie());
        assertThat(reported.status()).as(reported.body()).isEqualTo(201);
        Reply own = send(HttpMethod.POST, "/api/v1/reports",
                "{\"targetType\":\"EXTERNAL_POST\",\"targetId\":%d,\"reason\":\"SPAM\"}".formatted(postId),
                owner.cookie());
        assertThat(own.resultCode()).isEqualTo("CANNOT_REPORT_OWN_CONTENT");

        Reply kept = send(HttpMethod.POST, "/api/v1/me/external-blogs/" + blogId + "/release",
                "{\"deletePosts\":false}", owner.cookie());
        assertThat(kept.status()).as(kept.body()).isEqualTo(200);
        assertThat((String) kept.read("$.result.status")).isEqualTo("RELEASED");
        serveFeed(item("g2", "Flow second"), item("g1", "Flow first"));
        long readsAfterRelease = feedReads();
        Thread.sleep(3_000);
        assertThat(feedReads()).as("해제 뒤 피드를 다시 읽지 않음").isEqualTo(readsAfterRelease);
        assertThat(portal()).contains("Flow first").doesNotContain("Flow second");

        Reply deleted = send(HttpMethod.POST, "/api/v1/me/external-blogs/" + blogId + "/release",
                "{\"deletePosts\":true}", owner.cookie());
        assertThat(deleted.status()).as(deleted.body()).isEqualTo(200);
        assertThat(((Number) deleted.read("$.result.postCount")).intValue()).isZero();
        assertThat(portal()).doesNotContain("Flow first");
        assertThat(jdbc.queryForList("SELECT id FROM external_posts WHERE external_blog_id = ?", Long.class, blogId))
                .isEqualTo(List.of());
    }
}
