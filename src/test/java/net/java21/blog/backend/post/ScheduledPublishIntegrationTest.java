package net.java21.blog.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import net.java21.blog.backend.post.job.ScheduledPublishJob;
import net.java21.blog.backend.security.JwtAuthenticationFilter;
import net.java21.blog.backend.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 예약 발행 끝에서 끝까지(T080, US3 AS3, SC-010): 예약 글은 주인 외 상세 404이고 블로그 목록·RSS·사이트맵·서비스 태그·포털 어디에도
 * 없다. 시각을 넘기고 작업을 한 번 돌리면 모든 곳에 나오고 {@code publishedAt}은 작업 실행 시각이다. 전체 컨텍스트를 H2(MySQL 모드)로
 * 띄우고 시계는 {@link MutableClock}으로 움직인다(작업 주기는 테스트가 직접 부르도록 길게).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:scheduledpublish;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.base-url=https://blog.example.com",
        "blog.portal.cache-ttl=0s",
        "blog.portal.new-member-delay=0s",
        "blog.jobs.scheduled-publish-delay=1h"
})
class ScheduledPublishIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "password123";
    private static final String TAG = "schedtag";
    private static final String LONG = "가".repeat(250);
    private static final Instant START = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ScheduledPublishJob job;

    private long topicId;

    @Test
    void scheduledPostAppearsEverywhereOnlyAfterTheJobRuns() throws Exception {
        topicId = jdbc.queryForObject("SELECT id FROM topics WHERE slug = 'it-internet'", Long.class);
        Cookie owner = signup("sched@example.com", "sched");
        Cookie reader = signup("schedreader@example.com", "schedreader");
        long visible = draft(owner, "Visible");
        json(post("/api/v1/posts/" + visible + "/publish"), "{\"visibility\":\"PUBLIC\",\"topicId\":" + topicId + "}",
                owner).andExpect(status().isOk());

        Instant at = START.plusSeconds(20);
        long scheduled = draft(owner, "Scheduled");
        json(post("/api/v1/posts/" + scheduled + "/publish"), "{\"visibility\":\"PUBLIC\",\"topicId\":" + topicId
                + ",\"scheduledAt\":\"" + at + "\"}", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.result.scheduledAt").value(at.toString()));

        // 주인은 상세에서 예약 시각을 보고, 주인 외는 없는 글과 같은 404
        read(get("/api/v1/posts/" + scheduled), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.result.scheduledAt").value(at.toString()));
        for (Cookie viewer : new Cookie[] {null, reader}) {
            read(get("/api/v1/posts/" + scheduled), viewer).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"));
        }
        read(get("/api/v1/blogs/sched/manage/posts?status=SCHEDULED"), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(scheduled))
                .andExpect(jsonPath("$.result[0].scheduledAt").value(at.toString()));

        Map<String, String> before = surfaces();
        before.forEach((name, body) -> assertThat(body).as(name + " (발행 전)").doesNotContain("Scheduled"));
        assertThat(before.get("블로그 목록")).contains("Visible");
        assertThat(sitemap()).contains("/sched/" + visible).doesNotContain("/sched/" + scheduled);

        // 시각 전에는 작업이 돌아도 그대로
        clock.advance(Duration.ofSeconds(10));
        assertThat(job.publishDue()).isZero();
        read(get("/api/v1/posts/" + scheduled), null).andExpect(status().isNotFound());

        // 시각이 지난 뒤 첫 실행(주기 30초 → 1분 안)
        clock.advance(Duration.ofSeconds(25));
        Instant runAt = clock.instant();
        assertThat(job.publishDue()).isEqualTo(1);

        read(get("/api/v1/posts/" + scheduled), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.result.publishedAt").value(runAt.toString()))
                .andExpect(jsonPath("$.result.scheduledAt").doesNotExist());
        assertThat(Duration.between(at, runAt)).isLessThan(Duration.ofMinutes(1));
        Map<String, String> after = surfaces();
        after.forEach((name, body) -> assertThat(body).as(name + " (발행 후)").contains("Scheduled"));
        assertThat(ids(after.get("블로그 목록"))).containsExactly(scheduled, visible);
        assertThat(sitemap()).contains("/sched/" + scheduled);
    }

    /** 주인 외가 보는 곳: 블로그 목록·태그 목록, 서비스 태그, RSS, 포털 최신·주제. */
    private Map<String, String> surfaces() throws Exception {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("블로그 목록", body(get("/api/v1/blogs/sched/posts")));
        bodies.put("블로그 태그 목록", body(get("/api/v1/blogs/sched/posts?tag=" + TAG)));
        bodies.put("서비스 태그", body(get("/api/v1/tags/" + TAG + "/posts")));
        bodies.put("RSS", body(get("/sched/rss")));
        bodies.put("포털 최신", body(get("/api/v1/portal/latest")));
        bodies.put("주제 최신", body(get("/api/v1/topics/it-internet/posts")));
        return bodies;
    }

    /** 사이트맵은 제목 없이 주소만 싣는다. */
    private String sitemap() throws Exception {
        return body(get("/sitemap/posts-1.xml"));
    }

    private long draft(Cookie cookie, String title) throws Exception {
        MvcResult result = json(post("/api/v1/blogs/sched/posts/drafts"), "{\"title\":\"" + title
                + "\",\"contentMarkdown\":\"" + LONG + "\",\"tags\":[\"" + TAG + "\"],\"topicId\":" + topicId + "}",
                cookie).andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.id")).longValue();
    }

    private Cookie signup(String email, String handle) throws Exception {
        MvcResult result = json(post("/api/v1/auth/signup"), """
                {"email":"%s","password":"%s","nickname":"%s","handle":"%s",
                 "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06"}"""
                .formatted(email, PASSWORD, handle, handle), null)
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getCookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE);
    }

    private ResultActions read(MockHttpServletRequestBuilder builder, Cookie cookie) throws Exception {
        if (cookie != null) {
            builder.cookie(cookie);
        }
        return mvc.perform(builder);
    }

    private ResultActions json(MockHttpServletRequestBuilder builder, String body, Cookie cookie) throws Exception {
        builder.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
        return read(builder, cookie);
    }

    private String body(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<Long> ids(String json) {
        return JsonPath.<List<Number>>read(json, "$.result[*].id").stream().map(Number::longValue).toList();
    }
}
