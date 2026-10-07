package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 004 노출 확인(T121, 001 SC-004): 노출 매트릭스의 PROTECTED·SCHEDULED 행을 블로그 홈·카테고리·블로그 태그·서비스 태그·공지·
 * 보관함·사이드바·구독 피드·RSS·Atom·사이트맵·포털 메인·주제 페이지·관련 글에서 한 번에 확인한다. 보호 글은 제목만(본문 표지
 * {@link #LOCKED_BODY} 0건), 예약 글은 제목·본문 모두 0건({@link #SCHEDULED}). 비밀 댓글·비밀 방명록 내용은 권한 밖 응답에 0건,
 * 비회원 비밀번호 해시와 IP는 어떤 응답에도 없다. 전체 컨텍스트를 H2(MySQL 모드)로 띄운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:blogfeatureexposure;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.base-url=https://blog.example.com",
        "blog.portal.cache-ttl=0s",
        "blog.portal.new-member-delay=0s",
        "blog.portal.min-content-length=10"
})
class BlogFeatureExposureIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "password123";
    private static final String LOCKED_BODY = "LOCKEDBODY";
    private static final String SCHEDULED = "SCHEDULEDPOST";
    private static final String SECRET_COMMENT = "SECRETCOMMENT";
    private static final String SECRET_GUESTBOOK = "SECRETGUESTBOOK";
    private static final String GUEST_PASSWORD = "guestpass1";
    private static final String TAG = "matrix";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    private long topicId;

    @Test
    void protectedPostsShowTitlesOnlyAndScheduledPostsAndSecretsNeverLeak() throws Exception {
        topicId = jdbc.queryForObject("SELECT id FROM topics WHERE slug = 'it-internet'", Long.class);
        Cookie owner = signup("owner@example.com", "owner");
        Cookie reader = signup("reader@example.com", "reader");
        long category = id(json(post("/api/v1/blogs/owner/categories"), "{\"name\":\"Cat\"}", owner)
                .andExpect(status().isCreated()).andReturn());
        json(put("/api/v1/me/subscriptions/owner"), "", reader).andExpect(status().isOk());
        json(patch("/api/v1/blogs/owner"), "{\"guestWriteEnabled\":true}", owner).andExpect(status().isOk());

        long visible = publish(owner, "Visible", "공개 본문", category,
                "\"visibility\":\"PUBLIC\"");
        long locked = publish(owner, "Locked title", LOCKED_BODY + " 보호 본문", category,
                "\"visibility\":\"PROTECTED\",\"password\":\"open-sesame\"");
        long lockedNotice = publish(owner, "Locked notice", LOCKED_BODY + " 보호 공지 본문", category,
                "\"visibility\":\"PROTECTED\",\"password\":\"open-sesame\",\"notice\":true");
        String at = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
        long scheduled = publish(owner, SCHEDULED + " title", SCHEDULED + " 예약 본문", category,
                "\"visibility\":\"PUBLIC\",\"notice\":true,\"scheduledAt\":\"" + at + "\"");

        // 비밀 댓글(회원)·비밀 비회원 댓글·비밀 방명록·비회원 방명록
        json(post("/api/v1/posts/" + visible + "/comments"),
                "{\"content\":\"" + SECRET_COMMENT + " member\",\"secret\":true}", reader)
                .andExpect(status().isCreated());
        json(post("/api/v1/posts/" + visible + "/comments"), "{\"content\":\"" + SECRET_COMMENT
                + " guest\",\"secret\":true,\"guestName\":\"손님\",\"guestPassword\":\"" + GUEST_PASSWORD + "\"}", null)
                .andExpect(status().isCreated());
        json(post("/api/v1/posts/" + visible + "/comments"), "{\"content\":\"공개 비회원 댓글\",\"guestName\":\"나그네\","
                + "\"guestPassword\":\"" + GUEST_PASSWORD + "\"}", null).andExpect(status().isCreated());
        json(post("/api/v1/blogs/owner/guestbook"), "{\"content\":\"" + SECRET_GUESTBOOK + "\",\"secret\":true}", reader)
                .andExpect(status().isCreated());
        json(post("/api/v1/blogs/owner/guestbook"), "{\"content\":\"" + SECRET_GUESTBOOK + " guest\",\"secret\":true,"
                + "\"guestName\":\"손님\",\"guestPassword\":\"" + GUEST_PASSWORD + "\"}", null)
                .andExpect(status().isCreated());
        json(post("/api/v1/blogs/owner/guestbook"), "{\"content\":\"공개 방명록\",\"guestName\":\"손님\","
                + "\"guestPassword\":\"" + GUEST_PASSWORD + "\"}", null).andExpect(status().isCreated());
        for (long id : List.of(visible, locked)) {
            mvc.perform(post("/api/v1/posts/" + id + "/read-complete").header("Origin", ORIGIN));
        }

        Map<String, Cookie> viewers = new LinkedHashMap<>();
        viewers.put("비로그인", null);
        viewers.put("구독한 독자", reader);
        for (Map.Entry<String, Cookie> viewer : viewers.entrySet()) {
            Cookie cookie = viewer.getValue();
            Map<String, String> bodies = new LinkedHashMap<>();
            bodies.put("블로그 홈", ok("/api/v1/blogs/owner/posts", cookie));
            bodies.put("카테고리", ok("/api/v1/blogs/owner/posts?category=" + category, cookie));
            bodies.put("블로그 태그", ok("/api/v1/blogs/owner/posts?tag=" + TAG, cookie));
            bodies.put("서비스 태그", ok("/api/v1/tags/" + TAG + "/posts", cookie));
            bodies.put("공지", ok("/api/v1/blogs/owner/notices", cookie));
            bodies.put("보관함", ok("/api/v1/blogs/owner/archive", cookie));
            bodies.put("사이드바", ok("/api/v1/blogs/owner/sidebar", cookie));
            bodies.put("RSS", ok("/owner/rss", cookie));
            bodies.put("Atom", ok("/owner/atom", cookie));
            bodies.put("카테고리 RSS", ok("/owner/category/" + category + "/rss", cookie));
            bodies.put("사이트맵", ok("/sitemap/posts-1.xml", cookie));
            bodies.put("포털 메인", ok("/api/v1/portal", cookie));
            bodies.put("포털 최신", ok("/api/v1/portal/latest", cookie));
            bodies.put("주제", ok("/api/v1/topics/it-internet/posts", cookie));
            bodies.put("관련 글", ok("/api/v1/posts/" + visible + "/related", cookie));
            bodies.put("댓글", ok("/api/v1/posts/" + visible + "/comments", cookie));
            bodies.put("방명록", ok("/api/v1/blogs/owner/guestbook", cookie));
            bodies.put("보호 글 상세", body(read(get("/api/v1/posts/" + locked), cookie).andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.locked").value(true))
                    .andExpect(jsonPath("$.result.title").value("Locked title"))));
            bodies.put("보호 글 댓글", body(read(get("/api/v1/posts/" + locked + "/comments"), cookie)));
            bodies.put("예약 글 상세", body(read(get("/api/v1/posts/" + scheduled), cookie)
                    .andExpect(status().isNotFound())));
            if (cookie != null) {
                bodies.put("구독 피드", ok("/api/v1/me/feed", cookie));
            }

            // 보호 글은 블로그 목록·공지에 제목으로 남는다(요약 없음). 공지는 홈 목록에서 빠지고 공지 목록에만 나온다
            String home = bodies.get("블로그 홈");
            assertThat(ids(home, "$.result[*].id")).containsExactlyInAnyOrder(visible, locked);
            assertThat(JsonPath.<List<Object>>read(home, "$.result[?(@.id == " + locked + ")].summary"))
                    .containsOnlyNulls();
            assertThat(ids(bodies.get("공지"), "$.result[*].id")).containsExactly(lockedNotice);
            assertThat(bodies.get("RSS")).contains("Locked title");

            bodies.forEach((surface, text) -> {
                assertThat(text).as("%s · %s: 보호 글 본문", viewer.getKey(), surface).doesNotContain(LOCKED_BODY);
                assertThat(text).as("%s · %s: 예약 글", viewer.getKey(), surface).doesNotContain(SCHEDULED);
                assertThat(text).as("%s · %s: 비회원 비밀번호 해시", viewer.getKey(), surface)
                        .doesNotContain("$2a$", "$2b$", GUEST_PASSWORD);
                assertThat(text).as("%s · %s: 비회원 IP", viewer.getKey(), surface).doesNotContain("127.0.0.1");
                if (cookie == null) {
                    assertThat(text).as("%s · %s: 비밀 댓글", viewer.getKey(), surface).doesNotContain(SECRET_COMMENT);
                    assertThat(text).as("%s · %s: 비밀 방명록", viewer.getKey(), surface).doesNotContain(SECRET_GUESTBOOK);
                } else {
                    // 독자는 자기 비밀 댓글만 본다
                    assertThat(text).as("%s · %s: 남의 비밀 댓글", viewer.getKey(), surface)
                            .doesNotContain(SECRET_COMMENT + " guest", SECRET_GUESTBOOK + " guest");
                }
            });
            // 사이트맵은 본문을 볼 수 있는 글만(보호 글 상세는 noindex)
            assertThat(bodies.get("사이트맵")).contains("/owner/" + visible + "<")
                    .doesNotContain("/owner/" + locked + "<", "/owner/" + scheduled + "<");
        }

        // 주인은 예약 글과 비밀 내용을 그대로 본다(비회원 해시·IP는 주인에게도 없음)
        String manage = ok("/api/v1/blogs/owner/manage/posts?status=SCHEDULED", owner);
        assertThat(ids(manage, "$.result[*].id")).containsExactly(scheduled);
        String ownerComments = ok("/api/v1/posts/" + visible + "/comments", owner);
        assertThat(ownerComments).contains(SECRET_COMMENT + " member", SECRET_COMMENT + " guest")
                .doesNotContain("$2a$", GUEST_PASSWORD, "127.0.0.1");
        String ownerGuestbook = ok("/api/v1/blogs/owner/guestbook", owner);
        assertThat(ownerGuestbook).contains(SECRET_GUESTBOOK).doesNotContain("$2a$", GUEST_PASSWORD, "127.0.0.1");
        assertThat(ok("/api/v1/blogs/owner/manage/comments", owner)).doesNotContain("$2a$", GUEST_PASSWORD);
    }

    private long publish(Cookie cookie, String title, String text, long categoryId, String settings) throws Exception {
        long id = id(json(post("/api/v1/blogs/owner/posts/drafts"), "{\"title\":\"" + title
                + "\",\"contentMarkdown\":\"" + text + " " + "본문".repeat(20) + "\",\"categoryId\":" + categoryId
                + ",\"tags\":[\"" + TAG + "\"],\"topicId\":" + topicId + "}", cookie)
                .andExpect(status().isCreated()).andReturn());
        json(post("/api/v1/posts/" + id + "/publish"), "{" + settings + ",\"topicId\":" + topicId + "}", cookie)
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

    private String ok(String path, Cookie cookie) throws Exception {
        return body(read(get(path), cookie).andExpect(status().isOk()));
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

    private static List<Long> ids(String json, String path) {
        return JsonPath.<List<Number>>read(json, path).stream().map(Number::longValue).toList();
    }
}
