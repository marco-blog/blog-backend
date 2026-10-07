package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.feed.SummaryExtractor;
import net.java21.blog.backend.external.fetch.FeedCollector;
import net.java21.blog.backend.security.JwtAuthenticationFilter;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.StubHttpServer;
import net.java21.blog.backend.topic.domain.Topic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 007 T049: 외부 글의 노출 범위(FR-125)와 본문 비저장(SC-021). 외부 글은 포털(최신·인기·주제)에만 카드로 나오고, 블로그 RSS·Atom,
 * 사이트맵, 블로그 홈 목록, 태그 목록에는 나오지 않는다. 검색은 FULLTEXT가 필요해 {@code PostSearchRepositoryTest}(MySQL)의
 * {@code neverReturnsExternalPosts}가 확인한다. 본문 50KB 피드를 실제 수집기로 모은 뒤 {@code external_posts} 행에 본문 문장이
 * 없고 문자열 길이 합이 제목·요약·주소·식별자 몫을 넘지 않는지 본다. 피드는 루프백 {@link StubHttpServer}이므로
 * {@code blog.outbound.allow-private=true}(시험 전용)와 그 포트를 허용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:externalexclusion;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.base-url=https://blog.example.com",
        "blog.outbound.allow-private=true",
        "blog.external.poll-interval=1h"
})
class ExternalPostExclusionIntegrationTest {

    private static final StubHttpServer SERVER = StubHttpServer.start();
    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "password123";
    private static final String TAG = "matrix";
    private static final String EXTERNAL = "EXTONLY";
    private static final String BODY_SENTENCE = "Body sentence that must never be stored";
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    /** 해시·열거·분류 버전처럼 글 내용이 아닌 고정 길이 열. 내용 열의 합을 따질 때 뺀다. */
    private static final List<String> METADATA_COLUMNS = List.of("guid_hash", "link_hash", "thumbnail_key",
            "topic_source", "status", "removed_reason", "classifier_version");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private FeedCollector collector;

    @DynamicPropertySource
    static void outbound(DynamicPropertyRegistry registry) {
        registry.add("blog.outbound.allowed-ports", () -> "80,443," + SERVER.port());
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    void externalPostsStayOutOfBlogFeedsSitemapsListsAndKeepNoBody() throws Exception {
        Cookie owner = signup("owner@example.com", "owner");
        long visible = publish(owner, "owner", "Visible");

        String title = EXTERNAL + " title";
        String link = SERVER.uri("/posts/1").toString();
        String guid = "ext-guid-1";
        String body = body50k();
        SERVER.respond("/feed.xml", 200, "application/rss+xml; charset=UTF-8", rss(title, link, guid, body));
        long blogId = tx.execute(status -> {
            Topic topic = em.find(Topic.class,
                    jdbc.queryForObject("SELECT MIN(id) FROM topics WHERE parent_id IS NOT NULL", Long.class));
            return new ExternalFixtures(em).blog(null, SERVER.uri("/feed.xml").toString(), topic,
                    ExternalBlogStatus.ACTIVE).getId();
        });

        assertThat(collector.collect(blogId)).isEqualTo(FeedCollector.Outcome.OK);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM external_posts");
        assertThat(rows).hasSize(1);

        // FR-125: 포털 최신에는 카드로 나오지만, 블로그 피드·사이트맵·블로그 목록·태그 목록에는 없다
        assertThat(body(read(get("/api/v1/portal/latest")).andExpect(status().isOk()))).contains(title);
        List<String> bodies = new ArrayList<>();
        for (String path : List.of("/owner/rss", "/owner/atom", "/sitemap.xml", "/sitemap/pages.xml",
                "/sitemap/posts-1.xml", "/api/v1/blogs/owner/posts", "/api/v1/blogs/owner/tags",
                "/api/v1/tags/" + TAG + "/posts", "/api/v1/blogs/owner/archive")) {
            String text = body(read(get(path)).andExpect(status().isOk()));
            bodies.add(text);
        }
        assertThat(bodies.get(0)).contains("Visible", "https://blog.example.com/owner/" + visible);
        assertThat(bodies.get(5)).contains("Visible");
        assertThat(bodies.get(7)).contains("Visible");
        assertThat(bodies).as("외부 글이 내부 목록에 노출됨(FR-125)")
                .noneMatch(text -> text.contains(EXTERNAL) || text.contains(link));

        // SC-021: 본문 문장은 어느 열에도 없고, 내용 열의 길이 합은 제목·요약·주소·guid 몫을 넘지 않는다
        Map<String, Object> row = rows.get(0);
        int contentLength = 0;
        for (Map.Entry<String, Object> column : row.entrySet()) {
            if (column.getValue() instanceof CharSequence text) {
                assertThat(text.toString()).as(column.getKey()).doesNotContain(BODY_SENTENCE);
                if (!METADATA_COLUMNS.contains(column.getKey().toLowerCase())) {
                    contentLength += text.length();
                }
            }
        }
        String summary = (String) row.get("summary");
        assertThat(summary).hasSizeLessThanOrEqualTo(SummaryExtractor.SUMMARY_MAX);
        assertThat(row.get("title")).isEqualTo(title);
        assertThat(contentLength).isLessThanOrEqualTo(title.length() + SummaryExtractor.SUMMARY_MAX
                + link.length() + guid.length() + "[\"matrix\"]".length());
        assertThat(contentLength).isLessThan(body.length() / 50);
    }

    /** 요약 한도(200자)보다 긴 첫 문단 뒤에 본문 문장을 되풀이한 약 50KB 본문. 요약은 첫 문단 앞부분만 가질 수 있다. */
    private static String body50k() {
        StringBuilder sb = new StringBuilder("<p>");
        while (sb.length() < SummaryExtractor.SUMMARY_MAX + 100) {
            sb.append("Opening words for the summary. ");
        }
        sb.append("</p>");
        int n = 0;
        while (sb.length() < 50 * 1024) {
            sb.append("<p>").append(BODY_SENTENCE).append(" #").append(n++).append(".</p>");
        }
        return sb.toString();
    }

    private static String rss(String title, String link, String guid, String body) {
        String pubDate = RFC_1123.format(Instant.now().minus(1, ChronoUnit.DAYS));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>Ext</title><link>"
                + SERVER.uri("/") + "</link><description>d</description><item><guid isPermaLink=\"false\">" + guid
                + "</guid><title>" + title + "</title><link>" + link + "</link><pubDate>" + pubDate
                + "</pubDate><category>" + TAG + "</category><description><![CDATA[" + body
                + "]]></description></item></channel></rss>";
    }

    private long publish(Cookie cookie, String handle, String title) throws Exception {
        long id = id(json(post("/api/v1/blogs/" + handle + "/posts/drafts"),
                "{\"title\":\"" + title + "\",\"contentMarkdown\":\"본문 " + title + "\",\"tags\":[\"" + TAG + "\"]}",
                cookie).andExpect(status().isCreated()).andReturn());
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PUBLIC\"}", cookie)
                .andExpect(status().isOk());
        return id;
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

    private ResultActions read(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder);
    }

    private ResultActions json(MockHttpServletRequestBuilder builder, String body, Cookie cookie) throws Exception {
        builder.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookie != null) {
            builder.cookie(cookie);
        }
        return mvc.perform(builder);
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.id")).longValue();
    }
}
