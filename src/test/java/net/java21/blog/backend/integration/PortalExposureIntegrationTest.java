package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 003 포털 노출 확인(T126, SC-004·013): 001 노출 매트릭스 행 전체(PRIVATE·DRAFT·DELETED·삭제된 블로그·작성자 SUSPENDED·WITHDRAWN)와
 * FR-088 조건 다섯 가지(본문 노출 가능, 블로그 포털 켜짐, 포털 제외 아님, 가입 후 24시간, 본문 200자)를 포털 메인 모든 영역(추천·인기·최신·
 * 인기 태그·새 블로그)·최신 더 보기·주제 페이지(최신·인기)에서 한 번에 본다. 숨어야 하는 글은 제목에 {@link #SECRET}을 넣어 포털 응답에 한 번도
 * 나오지 않는지(0건) 확인하고, 포털 제외·포털 끔·새 회원·짧은 글은 블로그 목록·RSS·사이트맵에는 그대로 있는지 본다. 캐시는 끈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:portalexposure;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.base-url=https://blog.example.com",
        "blog.portal.cache-ttl=0s",
        "blog.portal.new-member-delay=24h",
        "blog.portal.min-content-length=200"
})
class PortalExposureIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "password123";
    private static final String SECRET = "SECRET";
    private static final String HIDDEN_TAG = "hiddentag";
    private static final String LONG = "가".repeat(250);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    private long topicId;

    @Test
    void portalShowsOnlyEligiblePostsButBlogSurfacesKeepThem() throws Exception {
        topicId = jdbc.queryForObject("SELECT id FROM topics WHERE slug = 'it-internet'", Long.class);
        Cookie owner = signup("owner@example.com", "owner");
        Cookie admin = signup("operator@example.com", "operator");
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE nickname = 'operator'");

        long visible = publish(owner, "owner", "Visible", LONG, "PUBLIC", false);
        long privatePost = publish(owner, "owner", SECRET + " private", LONG, "PRIVATE",
                true);
        long draft = draft(owner, "owner", SECRET + " draft", LONG, true);
        long trashed = publish(owner, "owner", SECRET + " trashed", LONG, "PUBLIC", true);
        json(delete("/api/v1/posts/" + trashed), "", owner).andExpect(status().isOk());
        long tooShort = publish(owner, "owner", SECRET + " short", "짧은 글", "PUBLIC", true);
        long excluded = publish(owner, "owner", SECRET + " excluded", LONG, "PUBLIC", true);

        json(post("/api/v1/blogs"), "{\"handle\":\"hidold\",\"title\":\"Old\"}", owner).andExpect(status().isCreated());
        long inDeletedBlog = publish(owner, "hidold", SECRET + " deleted blog", LONG, "PUBLIC", true);
        json(post("/api/v1/blogs"), "{\"handle\":\"hidoff\",\"title\":\"Off\"}", owner).andExpect(status().isCreated());
        long portalOff = publish(owner, "hidoff", SECRET + " portal off", LONG, "PUBLIC", true);
        Cookie suspended = signup("suspended@example.com", "hidsusp");
        long bySuspended = publish(suspended, "hidsusp", SECRET + " suspended", LONG, "PUBLIC", true);
        Cookie withdrawn = signup("withdrawn@example.com", "hidwith");
        long byWithdrawn = publish(withdrawn, "hidwith", SECRET + " withdrawn", LONG, "PUBLIC", true);
        Cookie newbie = signup("newbie@example.com", "hidnew");
        long byNewMember = publish(newbie, "hidnew", SECRET + " new member", LONG, "PUBLIC", true);

        // 새 회원 하나만 빼고 모두 이틀 전에 가입한 것으로(가입 후 24시간 조건)
        jdbc.update("UPDATE users SET created_at = DATEADD('DAY', -2, CURRENT_TIMESTAMP) WHERE nickname <> 'hidnew'");
        // 제외할 글을 먼저 추천해 둔다(제외되면 추천 영역에서도 빠져야 한다, FR-092)
        json(post("/api/v1/admin/portal/curations"), "{\"postId\":" + excluded
                + ",\"startsAt\":\"2020-01-01T00:00:00Z\",\"endsAt\":\"2099-01-01T00:00:00Z\"}", admin)
                .andExpect(status().isCreated());
        json(post("/api/v1/admin/portal/curations"), "{\"postId\":" + visible
                + ",\"startsAt\":\"2020-01-01T00:00:00Z\",\"endsAt\":\"2099-01-01T00:00:00Z\"}", admin)
                .andExpect(status().isCreated());
        json(put("/api/v1/admin/portal/exclusions/" + excluded), "{\"reason\":\"광고\"}", admin)
                .andExpect(status().isOk());
        json(patch("/api/v1/blogs/hidoff"), "{\"portalEnabled\":false}", owner).andExpect(status().isOk());
        json(delete("/api/v1/blogs/hidold"), "{\"password\":\"" + PASSWORD + "\"}", owner).andExpect(status().isOk());
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE nickname = 'hidsusp'");
        json(delete("/api/v1/me"), "{\"password\":\"" + PASSWORD + "\"}", withdrawn).andExpect(status().isOk());
        // 인기 점수: 모든 글에 끝까지 읽음
        for (long id : List.of(visible, privatePost, draft, trashed, tooShort, excluded, inDeletedBlog, portalOff,
                bySuspended, byWithdrawn, byNewMember)) {
            mvc.perform(post("/api/v1/posts/" + id + "/read-complete").header("Origin", ORIGIN));
        }

        Map<String, String> portal = new LinkedHashMap<>();
        portal.put("메인", body(get("/api/v1/portal")));
        portal.put("최신 더 보기", body(get("/api/v1/portal/latest")));
        portal.put("주제 최신", body(get("/api/v1/topics/it-internet/posts")));
        portal.put("주제 인기", body(get("/api/v1/topics/it-internet/posts?sort=popular")));
        portal.put("대분류 최신", body(get("/api/v1/topics/knowledge/posts")));
        portal.put("대분류 인기", body(get("/api/v1/topics/knowledge/posts?sort=popular")));
        // 노출 가능한 글이 하나라 다음 묶음은 없다(숨은 글이 커서 너머에 남아 있지도 않다)
        assertThat(portal.get("최신 더 보기")).doesNotContain("nextCursor");

        String home = portal.get("메인");
        assertThat(ids(home, "$.result.curations[*].id")).containsExactly(visible);
        assertThat(ids(home, "$.result.popular[*].id")).containsExactly(visible);
        assertThat(ids(home, "$.result.latest.items[*].id")).containsExactly(visible);
        assertThat(JsonPath.<List<String>>read(home, "$.result.popularTags[*].name")).containsExactly("matrix");
        assertThat(JsonPath.<List<Integer>>read(home, "$.result.popularTags[*].postCount")).containsExactly(1);
        assertThat(JsonPath.<List<String>>read(home, "$.result.newBlogs[*].handle")).containsExactly("owner");
        assertThat(ids(portal.get("최신 더 보기"), "$.result[*].id")).containsExactly(visible);
        assertThat(ids(portal.get("주제 최신"), "$.result[*].id")).containsExactly(visible);
        assertThat(ids(portal.get("주제 인기"), "$.result[*].id")).containsExactly(visible);
        assertThat(ids(portal.get("대분류 최신"), "$.result[*].id")).containsExactly(visible);
        List<String> leaks = new ArrayList<>();
        portal.forEach((name, text) -> {
            if (text.contains(SECRET) || text.contains(HIDDEN_TAG) || text.contains("\"hid")) {
                leaks.add(name);
            }
        });
        assertThat(leaks).as("포털에 노출되면 안 되는 글·태그·블로그가 나옴(SC-004·013)").isEmpty();

        // 포털 제외·짧은 글·새 회원 글·포털 끈 블로그 글은 블로그 목록·RSS·사이트맵에는 그대로(포털 열에만 영향)
        String ownerPosts = body(get("/api/v1/blogs/owner/posts"));
        assertThat(ids(ownerPosts, "$.result[*].id")).contains(visible, tooShort, excluded)
                .doesNotContain(privatePost, draft, trashed);
        assertThat(ids(body(get("/api/v1/blogs/hidoff/posts")), "$.result[*].id")).containsExactly(portalOff);
        assertThat(ids(body(get("/api/v1/blogs/hidnew/posts")), "$.result[*].id")).containsExactly(byNewMember);
        assertThat(body(get("/owner/rss"))).contains("/owner/" + excluded, "/owner/" + tooShort);
        assertThat(body(get("/hidoff/rss"))).contains("/hidoff/" + portalOff);
        String sitemap = body(get("/sitemap/posts-1.xml"));
        for (long id : List.of(visible, tooShort, excluded, portalOff, byNewMember)) {
            assertThat(sitemap).contains("/" + id + "</loc>");
        }
        for (long id : List.of(privatePost, draft, trashed, inDeletedBlog, bySuspended, byWithdrawn)) {
            assertThat(sitemap).doesNotContain("/" + id + "</loc>");
        }
        // 관리자 글 찾기는 숨은 이유를 알려 준다
        String lookup = body(get("/api/v1/admin/portal/posts/" + excluded), admin);
        assertThat(JsonPath.<List<String>>read(lookup, "$.result.ineligibleReasons")).containsExactly("EXCLUDED");
        assertThat(JsonPath.<List<String>>read(body(get("/api/v1/admin/portal/posts/" + tooShort), admin),
                "$.result.ineligibleReasons")).containsExactly("TOO_SHORT");
        assertThat(JsonPath.<List<String>>read(body(get("/api/v1/admin/portal/posts/" + byNewMember), admin),
                "$.result.ineligibleReasons")).containsExactly("NEW_MEMBER");
        assertThat(JsonPath.<List<String>>read(body(get("/api/v1/admin/portal/posts/" + portalOff), admin),
                "$.result.ineligibleReasons")).containsExactly("BLOG_PORTAL_DISABLED");
        assertThat(JsonPath.<List<String>>read(body(get("/api/v1/admin/portal/posts/" + privatePost), admin),
                "$.result.ineligibleReasons")).contains("NOT_BODY_VISIBLE");
    }

    private long publish(Cookie cookie, String handle, String title, String text, String visibility, boolean hidden)
            throws Exception {
        long id = draft(cookie, handle, title, text, hidden);
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"" + visibility + "\",\"topicId\":"
                + topicId + "}", cookie).andExpect(status().isOk());
        return id;
    }

    private long draft(Cookie cookie, String handle, String title, String text, boolean hidden) throws Exception {
        String tags = hidden ? "[\"matrix\",\"" + HIDDEN_TAG + "\"]" : "[\"matrix\"]";
        MvcResult result = json(post("/api/v1/blogs/" + handle + "/posts/drafts"), "{\"title\":\"" + title
                + "\",\"contentMarkdown\":\"" + text + "\",\"tags\":" + tags + ",\"topicId\":" + topicId + "}", cookie)
                .andExpect(status().isCreated())
                .andReturn();
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

    private ResultActions json(MockHttpServletRequestBuilder builder, String body, Cookie cookie) throws Exception {
        builder.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookie != null) {
            builder.cookie(cookie);
        }
        return mvc.perform(builder);
    }

    private String body(MockHttpServletRequestBuilder builder) throws Exception {
        return body(builder, null);
    }

    private String body(MockHttpServletRequestBuilder builder, Cookie cookie) throws Exception {
        if (cookie != null) {
            builder.cookie(cookie);
        }
        return mvc.perform(builder).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<Long> ids(String json, String path) {
        return JsonPath.<List<Number>>read(json, path).stream().map(Number::longValue).toList();
    }
}
