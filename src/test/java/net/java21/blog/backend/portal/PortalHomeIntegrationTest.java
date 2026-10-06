package net.java21.blog.backend.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import net.java21.blog.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 포털 노출 확인(003 T040, SC-004, SC-014): 글 노출 매트릭스 001 행 전부(PRIVATE·DRAFT·DELETED·삭제된 블로그·작성자 SUSPENDED·
 * WITHDRAWN)와 FR-088 조건 다섯 가지(포털 끔·제외·가입 24시간 안·본문 200자 미만, 본문 노출 가능)의 글이 {@code /api/v1/portal}의
 * 모든 영역과 {@code /portal/latest}에 한 번도 나오지 않고, 한 블로그의 5편은 영역마다 2편까지만 나온다. 캐시는 끈다({@code 0s}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:portalhome;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.portal.cache-ttl=0s"
})
class PortalHomeIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "password123";
    private static final String SECRET = "SECRET";
    private static final String LONG = "가".repeat(250);
    private static final String SHORT = "가".repeat(150);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void hiddenPostsNeverAppearAndEachBlogShowsAtMostTwoPostsPerSection() throws Exception {
        Cookie owner = signup("owner@example.com", "owner");
        Cookie other = signup("other@example.com", "other");
        Cookie reader = signup("reader@example.com", "reader");
        List<Long> ownerPosts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ownerPosts.add(publish(owner, "owner", "Owner " + i, LONG, "PUBLIC", "spring"));
        }
        long otherPost = publish(other, "other", "Other", LONG, "PUBLIC", "spring");

        Map<Long, String> hidden = new HashMap<>();
        hidden.put(publish(owner, "owner", SECRET + " private", LONG, "PRIVATE", "secret"), "PRIVATE");
        hidden.put(draft(owner, "owner", SECRET + " draft", LONG, "secret"), "DRAFT");
        long trashed = publish(owner, "owner", SECRET + " trashed", LONG, "PUBLIC", "secret");
        json(delete("/api/v1/posts/" + trashed), "", owner).andExpect(status().isOk());
        hidden.put(trashed, "DELETED");
        hidden.put(publish(owner, "owner", SECRET + " short", SHORT, "PUBLIC", "secret"), "본문 200자 미만");
        long excluded = publish(owner, "owner", SECRET + " excluded", LONG, "PUBLIC", "secret");
        hidden.put(excluded, "포털 제외");

        json(post("/api/v1/blogs"), "{\"handle\":\"ownerold\",\"title\":\"Old\"}", owner)
                .andExpect(status().isCreated());
        hidden.put(publish(owner, "ownerold", SECRET + " deleted blog", LONG, "PUBLIC", "secret"), "삭제된 블로그");
        json(post("/api/v1/blogs"), "{\"handle\":\"owneroff\",\"title\":\"Off\"}", owner)
                .andExpect(status().isCreated());
        hidden.put(publish(owner, "owneroff", SECRET + " portal off", LONG, "PUBLIC", "secret"), "포털 끔");
        Cookie suspended = signup("suspended@example.com", "suspended");
        hidden.put(publish(suspended, "suspended", SECRET + " suspended", LONG, "PUBLIC", "secret"), "SUSPENDED");
        Cookie withdrawn = signup("withdrawn@example.com", "withdrawn");
        hidden.put(publish(withdrawn, "withdrawn", SECRET + " withdrawn", LONG, "PUBLIC", "secret"), "WITHDRAWN");
        Cookie fresh = signup("fresh@example.com", "fresh");
        hidden.put(publish(fresh, "fresh", SECRET + " new member", LONG, "PUBLIC", "secret"), "가입 24시간 안");

        // 인기 신호: 모든 글에 조회·끝까지 읽음·좋아요(보이지 않는 글은 404라 신호가 없다 — 주인 조회로 넣는다)
        for (long id : allIds(ownerPosts, otherPost, hidden)) {
            read(post("/api/v1/posts/" + id + "/views").header("Origin", ORIGIN), owner);
            read(post("/api/v1/posts/" + id + "/read-complete").header("Origin", ORIGIN), owner);
            json(put("/api/v1/me/likes/" + id), "", reader);
        }
        jdbc.update("INSERT INTO post_daily_stats (post_id, stat_date, views, read_completes, created_at, updated_at)"
                + " SELECT id, CURRENT_DATE, 10, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM posts"
                + " WHERE id NOT IN (SELECT post_id FROM post_daily_stats)");

        long ownerId = userIdOf("owner");
        for (long id : List.of(hidden.keySet().iterator().next(), ownerPosts.get(0), excluded)) {
            jdbc.update("INSERT INTO portal_curations (created_by, post_id, starts_at, ends_at, sort_order, created_at,"
                    + " updated_at) VALUES (?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", ownerId, id,
                    Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                    Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));
        }
        jdbc.update("INSERT INTO portal_exclusions (excluded_by, post_id, reason, created_at, updated_at)"
                + " VALUES (?, ?, '광고', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", ownerId, excluded);
        json(delete("/api/v1/blogs/ownerold"), "{\"password\":\"" + PASSWORD + "\"}", owner).andExpect(status().isOk());
        jdbc.update("UPDATE blogs SET portal_enabled = FALSE WHERE handle = 'owneroff'");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", userIdOf("suspended"));
        json(delete("/api/v1/me"), "{\"password\":\"" + PASSWORD + "\"}", withdrawn).andExpect(status().isOk());
        Timestamp twoDaysAgo = Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS));
        jdbc.update("UPDATE users SET created_at = ? WHERE id <> ?", twoDaysAgo, userIdOf("fresh"));

        for (Cookie viewer : new Cookie[] {null, reader, owner}) {
            String home = body(read(get("/api/v1/portal"), viewer).andExpect(status().isOk()));
            assertThat(home).as("포털 메인에 숨은 글이 노출됨(SC-004)").doesNotContain(SECRET, "\"secret\"");

            assertThat(ids(home, "$.result.curations[*].id")).containsExactly(ownerPosts.get(0));
            List<Long> popular = ids(home, "$.result.popular[*].id");
            List<Long> latest = ids(home, "$.result.latest.items[*].id");
            for (List<Long> section : List.of(popular, latest)) {
                assertThat(section).hasSize(3).contains(otherPost);
                assertThat(section.stream().filter(ownerPosts::contains)).hasSize(2);
            }
            assertThat(latest).containsExactly(otherPost, ownerPosts.get(4), ownerPosts.get(3));
            assertThat(JsonPath.<List<String>>read(home, "$.result.popularTags[*].name")).containsExactly("spring");
            assertThat(JsonPath.<List<Integer>>read(home, "$.result.popularTags[*].postCount")).containsExactly(6);
            assertThat(JsonPath.<List<String>>read(home, "$.result.newBlogs[*].handle"))
                    .containsExactly("other", "owner");

            String next = body(read(get("/api/v1/portal/latest"), viewer).andExpect(status().isOk()));
            assertThat(next).doesNotContain(SECRET);
            assertThat(ids(next, "$.result[*].id")).isEqualTo(latest);
        }
        read(get("/api/v1/portal/latest").param("cursor", "nope"), null).andExpect(status().isBadRequest());
    }

    private static List<Long> allIds(List<Long> ownerPosts, long otherPost, Map<Long, String> hidden) {
        List<Long> all = new ArrayList<>(ownerPosts);
        all.add(otherPost);
        all.addAll(hidden.keySet());
        return all;
    }

    private long userIdOf(String handle) {
        return jdbc.queryForObject("SELECT user_id FROM blogs WHERE handle = ?", Long.class, handle);
    }

    private static List<Long> ids(String json, String path) {
        return JsonPath.<List<Number>>read(json, path).stream().map(Number::longValue).toList();
    }

    private long publish(Cookie cookie, String handle, String title, String text, String visibility, String tag)
            throws Exception {
        long id = draft(cookie, handle, title, text, tag);
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"" + visibility + "\"}", cookie)
                .andExpect(status().isOk());
        return id;
    }

    private long draft(Cookie cookie, String handle, String title, String text, String tag) throws Exception {
        return id(json(post("/api/v1/blogs/" + handle + "/posts/drafts"),
                "{\"title\":\"" + title + "\",\"contentMarkdown\":\"" + text + "\",\"tags\":[\"" + tag + "\"]}",
                cookie)
                .andExpect(status().isCreated())
                .andReturn());
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

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.id")).longValue();
    }
}
