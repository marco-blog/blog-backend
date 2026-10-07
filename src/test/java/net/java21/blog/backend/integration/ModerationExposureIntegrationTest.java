package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
 * 005 노출 확인(T105, 001 SC-004): 노출 매트릭스의 HIDDEN(관리자 숨김)·SUSPENDED(작성자 정지) 행을 블로그 홈·카테고리·블로그 태그·
 * 서비스 태그·공지·보관함·사이드바·구독 피드·RSS·Atom·사이트맵·포털 메인·포털 최신·주제·관련 글·트랙백 목록(출처 글)에서 한 번에
 * 확인한다(주인 외 0건). 숨긴 댓글·방명록·트랙백은 권한 밖 응답에 0건이고, 정지 회원 블로그는 {@code BLOG_RESTRICTED}다. 권리 침해
 * 신고의 연락 이메일·트랙백 송신 IP·신고자 신원은 관리자 외 어떤 응답에도 없다. 전체 컨텍스트를 H2(MySQL 모드)로 띄운다.
 * <p>검색({@code /search/posts})은 MySQL 전문 검색 전용이라 H2에서 돌지 않는다. 같은 노출 조건({@code PostExposure})을 쓰고, HIDDEN
 * 행은 {@code PostSearchRepositoryTest}(MySQL)가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:moderationexposure;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.base-url=https://blog.example.com",
        "blog.portal.cache-ttl=0s",
        "blog.portal.new-member-delay=0s",
        "blog.portal.min-content-length=10",
        // 같은 IP에서 회원 여섯을 만든다(가입 한도 IP당 1시간 5회).
        "blog.ratelimit.signup-per-ip-per-hour=1000"
})
class ModerationExposureIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "password123";
    private static final String TAG = "modmatrix";
    private static final String HIDDEN_POST = "HIDDENPOSTMARK";
    private static final String SUSPENDED = "SUSPENDEDMARK";
    private static final String HIDDEN_COMMENT = "HIDDENCOMMENTMARK";
    private static final String HIDDEN_GUESTBOOK = "HIDDENGUESTBOOKMARK";
    private static final String HIDDEN_TRACKBACK = "HIDDENTRACKBACKMARK";
    private static final String CONTACT_EMAIL = "rights-holder@contact.example.org";
    private static final String REPORTER = "tattlerzz";
    private static final String TRACKBACK_IP = "127.0.0.1";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    private long topicId;

    @Test
    void hiddenAndSuspendedContentNeverLeaksAndPrivateModerationDataStaysWithAdmins() throws Exception {
        topicId = jdbc.queryForObject("SELECT id FROM topics WHERE slug = 'it-internet'", Long.class);
        Member owner = signup("owner@example.com", "owner");
        Member reader = signup("reader@example.com", "reader");
        Member commenter = signup("commenter@example.com", "commenter");
        Member suspended = signup("suspended@example.com", "suspended");
        Member reporter = signup("reporter@example.com", REPORTER);
        Member admin = signup("admin@example.com", "moderator");
        jdbc.update("UPDATE users SET role = 'SUPER_ADMIN' WHERE id = ?", admin.id());

        long category = id(json(post("/api/v1/blogs/owner/categories"), "{\"name\":\"Cat\"}", owner.cookie())
                .andExpect(status().isCreated()).andReturn());
        long suspendedCategory = id(json(post("/api/v1/blogs/suspended/categories"), "{\"name\":\"Cat\"}",
                suspended.cookie()).andExpect(status().isCreated()).andReturn());
        for (String handle : List.of("owner", "suspended")) {
            json(put("/api/v1/me/subscriptions/" + handle), "", reader.cookie()).andExpect(status().isOk());
        }
        json(patch("/api/v1/blogs/owner"), "{\"guestWriteEnabled\":true}", owner.cookie()).andExpect(status().isOk());

        long visible = publish(owner.cookie(), "owner", "Visible", "공개 본문", category, "\"visibility\":\"PUBLIC\"");
        long hidden = publish(owner.cookie(), "owner", HIDDEN_POST + " title", HIDDEN_POST + " 숨길 본문", category,
                "\"visibility\":\"PUBLIC\",\"notice\":true");
        // 정지될 회원의 글: 받은 글(visible)에 서비스 안 트랙백을 보낸다(출처 글이 정지되면 트랙백 목록에서도 빠진다)
        long suspendedPost = publish(suspended.cookie(), "suspended", SUSPENDED + " title", SUSPENDED + " 본문",
                suspendedCategory, "\"visibility\":\"PUBLIC\",\"notice\":true,\"trackbackUrls\":"
                        + "[\"https://blog.example.com/owner/" + visible + "\"]");

        // 숨길 댓글·방명록, 외부 트랙백 둘(하나는 숨김)
        long comment = id(json(post("/api/v1/posts/" + visible + "/comments"),
                "{\"content\":\"" + HIDDEN_COMMENT + " 댓글\"}", commenter.cookie())
                .andExpect(status().isCreated()).andReturn());
        json(post("/api/v1/posts/" + visible + "/comments"), "{\"content\":\"보이는 댓글\"}", commenter.cookie())
                .andExpect(status().isCreated());
        long guestbook = id(json(post("/api/v1/blogs/owner/guestbook"),
                "{\"content\":\"" + HIDDEN_GUESTBOOK + " 방명록\"}", commenter.cookie())
                .andExpect(status().isCreated()).andReturn());
        ping(visible, "https://ext.example.net/p/1", HIDDEN_TRACKBACK + " title");
        ping(visible, "https://ext.example.net/p/2", "Visible trackback");
        String managed = ok("/api/v1/blogs/owner/manage/trackbacks", owner.cookie());
        long hiddenTrackback = JsonPath.<List<Number>>read(managed,
                "$.result[?(@.title == '" + HIDDEN_TRACKBACK + " title')].id").getFirst().longValue();
        assertThat(JsonPath.<List<Object>>read(managed, "$.result[*].id")).hasSize(3);

        // 신고(신고자 신원)·권리 침해 신고(연락 이메일)
        json(post("/api/v1/reports"), "{\"targetType\":\"POST\",\"targetId\":" + hidden
                + ",\"reason\":\"SPAM\",\"detail\":\"광고 글\"}", reporter.cookie()).andExpect(status().isCreated());
        json(post("/api/v1/rights-requests"), "{\"targetUrl\":\"https://blog.example.com/owner/" + hidden
                + "\",\"reason\":\"COPYRIGHT\",\"rightsBasis\":\"제 글을 무단 전재\",\"contactEmail\":\"" + CONTACT_EMAIL
                + "\",\"captchaToken\":\"x\"}", null).andExpect(status().isAccepted());

        // 관리자: 글·댓글·방명록·트랙백 숨김, 회원 정지
        for (String target : List.of("posts/" + hidden, "comments/" + comment, "guestbook-entries/" + guestbook,
                "trackbacks/" + hiddenTrackback)) {
            json(put("/api/v1/admin/contents/" + target + "/hidden"), "{\"reason\":\"신고 처리\"}", admin.cookie())
                    .andExpect(status().isOk());
        }
        json(post("/api/v1/admin/users/" + suspended.id() + "/suspend"), "{\"reason\":\"스팸\"}", admin.cookie())
                .andExpect(status().isOk());

        Map<String, Cookie> viewers = new LinkedHashMap<>();
        viewers.put("비로그인", null);
        viewers.put("구독한 독자", reader.cookie());
        viewers.put("신고한 회원", reporter.cookie());
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
            bodies.put("사이트맵 페이지", ok("/sitemap/pages.xml", cookie));
            bodies.put("포털 메인", ok("/api/v1/portal", cookie));
            bodies.put("포털 최신", ok("/api/v1/portal/latest", cookie));
            bodies.put("주제", ok("/api/v1/topics/it-internet/posts", cookie));
            bodies.put("관련 글", ok("/api/v1/posts/" + visible + "/related", cookie));
            bodies.put("글 상세", ok("/api/v1/posts/" + visible, cookie));
            bodies.put("트랙백 목록", ok("/api/v1/posts/" + visible + "/trackbacks", cookie));
            bodies.put("댓글", ok("/api/v1/posts/" + visible + "/comments", cookie));
            bodies.put("방명록", ok("/api/v1/blogs/owner/guestbook", cookie));
            bodies.put("숨긴 글 상세", body(read(get("/api/v1/posts/" + hidden), cookie)
                    .andExpect(status().isNotFound())));
            bodies.put("숨긴 글 트랙백", body(read(get("/api/v1/posts/" + hidden + "/trackbacks"), cookie)));
            bodies.put("정지 회원 블로그", body(read(get("/api/v1/blogs/suspended"), cookie)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.header.resultCode").value("BLOG_RESTRICTED"))));
            // 하위 API는 001 그대로 BLOG_NOT_FOUND(005 contracts "001~004 응답 확장")
            bodies.put("정지 회원 글 목록", body(read(get("/api/v1/blogs/suspended/posts"), cookie)
                    .andExpect(status().isNotFound())));
            bodies.put("정지 회원 글 상세", body(read(get("/api/v1/posts/" + suspendedPost), cookie)
                    .andExpect(status().isNotFound())));
            bodies.put("정지 회원 RSS", body(read(get("/suspended/rss"), cookie)));
            if (cookie != null) {
                bodies.put("구독 피드", ok("/api/v1/me/feed", cookie));
                bodies.put("알림", ok("/api/v1/me/notifications", cookie));
                bodies.put("내 정보", ok("/api/v1/me", cookie));
            }

            assertThat(ids(bodies.get("블로그 홈"), "$.result[*].id")).containsExactly(visible);
            assertThat(JsonPath.<Integer>read(bodies.get("트랙백 목록"), "$.totalCount")).isEqualTo(1);
            assertThat(JsonPath.<Integer>read(bodies.get("글 상세"), "$.result.trackbackCount")).isEqualTo(1);
            bodies.forEach((surface, text) -> {
                String at = viewer.getKey() + " · " + surface;
                assertThat(text).as("%s: 숨긴 글", at).doesNotContain(HIDDEN_POST);
                assertThat(text).as("%s: 정지 회원 글", at).doesNotContain(SUSPENDED);
                assertThat(text).as("%s: 숨긴 댓글", at).doesNotContain(HIDDEN_COMMENT);
                assertThat(text).as("%s: 숨긴 방명록", at).doesNotContain(HIDDEN_GUESTBOOK);
                assertThat(text).as("%s: 숨긴 트랙백", at).doesNotContain(HIDDEN_TRACKBACK);
                assertThat(text).as("%s: 권리 침해 연락 이메일", at).doesNotContain(CONTACT_EMAIL);
                assertThat(text).as("%s: 트랙백 송신 IP", at).doesNotContain(TRACKBACK_IP);
                if (cookie != reporter.cookie()) {
                    assertThat(text).as("%s: 신고자", at).doesNotContain(REPORTER);
                }
            });
            assertThat(bodies.get("사이트맵")).contains("/owner/" + visible + "<")
                    .doesNotContain("/owner/" + hidden + "<", "/suspended/" + suspendedPost + "<");
            assertThat(bodies.get("사이트맵 페이지")).doesNotContain("/suspended<");
        }

        // 주인은 숨긴 글(hidden: true)과 받은 트랙백 관리의 숨긴 트랙백을 본다. 숨긴 댓글·방명록은 작성 회원만 내용과 hidden: true로
        // 본다(005 contracts "001~004 응답 확장"). 신고자·연락 이메일·송신 IP는 주인·작성자에게도 없다.
        Map<String, String> privileged = new LinkedHashMap<>();
        privileged.put("주인 · 숨긴 글 상세", body(read(get("/api/v1/posts/" + hidden), owner.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.hidden").value(true))));
        privileged.put("주인 · 관리 글 목록", ok("/api/v1/blogs/owner/manage/posts", owner.cookie()));
        privileged.put("주인 · 댓글", ok("/api/v1/posts/" + visible + "/comments", owner.cookie()));
        privileged.put("주인 · 방명록", ok("/api/v1/blogs/owner/guestbook", owner.cookie()));
        privileged.put("주인 · 받은 트랙백", ok("/api/v1/blogs/owner/manage/trackbacks", owner.cookie()));
        privileged.put("주인 · 알림", ok("/api/v1/me/notifications", owner.cookie()));
        privileged.put("작성자 · 댓글", ok("/api/v1/posts/" + visible + "/comments", commenter.cookie()));
        privileged.put("작성자 · 방명록", ok("/api/v1/blogs/owner/guestbook", commenter.cookie()));
        assertThat(privileged.get("주인 · 관리 글 목록")).contains(HIDDEN_POST);
        assertThat(privileged.get("주인 · 받은 트랙백")).contains(HIDDEN_TRACKBACK);
        assertThat(privileged.get("주인 · 댓글")).doesNotContain(HIDDEN_COMMENT);
        assertThat(privileged.get("주인 · 방명록")).doesNotContain(HIDDEN_GUESTBOOK);
        assertThat(JsonPath.<List<Boolean>>read(privileged.get("작성자 · 댓글"),
                "$.result[?(@.content =~ /" + HIDDEN_COMMENT + ".*/)].hidden")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(privileged.get("작성자 · 방명록"),
                "$.result[?(@.content =~ /" + HIDDEN_GUESTBOOK + ".*/)].hidden")).containsExactly(true);
        privileged.forEach((surface, text) -> assertThat(text).as(surface)
                .doesNotContain(CONTACT_EMAIL, TRACKBACK_IP, REPORTER));
        // 정지된 회원 본인도 블로그는 BLOG_RESTRICTED(로그인은 막히고 남은 토큰으로 와도 같다)
        read(get("/api/v1/blogs/suspended"), suspended.cookie()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BLOG_RESTRICTED"));

        // 관리자는 신고 상세에서 연락 이메일과 신고자를 본다
        List<Long> reportIds = jdbc.queryForList("SELECT id FROM reports ORDER BY id", Long.class);
        assertThat(reportIds).hasSize(2);
        StringBuilder details = new StringBuilder();
        for (long reportId : reportIds) {
            details.append(ok("/api/v1/admin/reports/" + reportId, admin.cookie()));
        }
        assertThat(details.toString()).contains(CONTACT_EMAIL, REPORTER);
    }

    /** 외부 블로그가 보내는 트랙백(TrackBack 1.2 폼). 송신 IP는 MockMvc 기본값 127.0.0.1. */
    private void ping(long postId, String url, String title) throws Exception {
        String xml = body(mvc.perform(post("/owner/" + postId + "/trackback")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("url=" + url + "&title=" + title.replace(" ", "+") + "&excerpt=ext&blog_name=Ext"))
                .andExpect(status().isOk()));
        assertThat(xml).contains("<error>0</error>");
    }

    private long publish(Cookie cookie, String handle, String title, String text, long categoryId, String settings)
            throws Exception {
        long id = id(json(post("/api/v1/blogs/" + handle + "/posts/drafts"), "{\"title\":\"" + title
                + "\",\"contentMarkdown\":\"" + text + " " + "본문".repeat(20) + "\",\"categoryId\":" + categoryId
                + ",\"tags\":[\"" + TAG + "\"],\"topicId\":" + topicId + "}", cookie)
                .andExpect(status().isCreated()).andReturn());
        json(post("/api/v1/posts/" + id + "/publish"), "{" + settings + ",\"topicId\":" + topicId + "}", cookie)
                .andExpect(status().isOk());
        return id;
    }

    private record Member(long id, Cookie cookie) {
    }

    private Member signup(String email, String handle) throws Exception {
        MvcResult result = json(post("/api/v1/auth/signup"), """
                {"email":"%s","password":"%s","nickname":"%s","handle":"%s",
                 "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06"}"""
                .formatted(email, PASSWORD, handle, handle), null)
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.userId")).longValue();
        return new Member(id, result.getResponse().getCookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE));
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
