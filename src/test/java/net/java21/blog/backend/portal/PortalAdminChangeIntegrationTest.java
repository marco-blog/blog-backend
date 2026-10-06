package net.java21.blog.backend.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
 * 운영자 변경의 즉시 반영(003 T095, FR-094, SC-007): 포털 캐시를 5분으로 켜 둔 채로 제외·해제, 주제 숨김, 운영 설정, 추천을 바꾸면 다음
 * 요청부터 포털 응답이 바뀐다(커밋 뒤 캐시 비우기). 모든 변경은 작업 기록에 남는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:portaladminchange;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.portal.cache-ttl=5m",
        "blog.portal.new-member-delay=0s"
})
class PortalAdminChangeIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String TEXT = "가".repeat(250);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void adminChangesShowOnTheNextPortalRequest() throws Exception {
        Cookie owner = signup("adminchange@example.com", "adminchange");
        Cookie admin = signup("operator@example.com", "operator");
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE nickname = ?", "operator");
        long travel = topicId("domestic-travel");
        long id = draft(owner, "adminchange", "{\"title\":\"여행 글\",\"contentMarkdown\":\"" + TEXT + "\"}");
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PUBLIC\",\"topicId\":" + travel + "}",
                owner).andExpect(status().isOk());

        // 캐시를 채운다
        assertThat(latestIds()).contains(id);
        assertThat(topicPostIds("domestic-travel")).containsExactly(id);

        // 제외 → 바로 빠진다. 블로그 목록에는 남는다
        json(put("/api/v1/admin/portal/exclusions/" + id), "{\"reason\":\"광고\"}", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reason").value("광고"));
        assertThat(latestIds()).doesNotContain(id);
        assertThat(topicPostIds("domestic-travel")).isEmpty();
        assertThat(body(mvc.perform(get("/api/v1/blogs/adminchange/posts")))).contains("\"id\":" + id);
        mvc.perform(get("/api/v1/admin/portal/posts/" + id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ineligibleReasons[0]").value("EXCLUDED"));
        json(post("/api/v1/admin/portal/curations"), curation(id), admin)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_PORTAL_ELIGIBLE"));

        // 해제 → 바로 돌아온다
        json(delete("/api/v1/admin/portal/exclusions/" + id), "", admin).andExpect(status().isOk());
        assertThat(latestIds()).contains(id);

        // 운영 설정: 최소 본문 길이를 올리면 빠지고, 기본값으로 되돌리면 돌아온다
        json(put("/api/v1/admin/settings/portal.min-content-length"), "{\"value\":1000}", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.overridden").value(true));
        assertThat(latestIds()).doesNotContain(id);
        json(delete("/api/v1/admin/settings/portal.min-content-length"), "", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.overridden").value(false));
        assertThat(latestIds()).contains(id);

        // 추천 → 메인 추천 칸에 바로 나온다
        json(post("/api/v1/admin/portal/curations"), curation(id), admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.status").value("ACTIVE"))
                .andExpect(jsonPath("$.result.portalEligible").value(true));
        assertThat(ids(body(mvc.perform(get("/api/v1/portal"))), "$.result.curations[*].id")).containsExactly(id);

        // 주제 숨김 → 공개 주제 목록과 탭에서 바로 빠진다
        assertThat(body(mvc.perform(get("/api/v1/topics")))).contains("\"domestic-travel\"");
        json(patch("/api/v1/admin/topics/" + travel), "{\"adminHidden\":true}", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.effectiveHidden").value(true));
        assertThat(body(mvc.perform(get("/api/v1/topics")))).doesNotContain("\"domestic-travel\"");

        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_logs ORDER BY id", String.class))
                .containsExactly("PORTAL_EXCLUDE", "PORTAL_UNEXCLUDE", "SETTING_CHANGE", "SETTING_CHANGE",
                        "CURATION_CREATE", "TOPIC_HIDE");
    }

    private static String curation(long id) {
        return "{\"postId\":" + id + ",\"startsAt\":\"2020-01-01T00:00:00Z\",\"endsAt\":\"2099-01-01T00:00:00Z\"}";
    }

    private List<Long> latestIds() throws Exception {
        return ids(body(mvc.perform(get("/api/v1/portal/latest")).andExpect(status().isOk())), "$.result[*].id");
    }

    private List<Long> topicPostIds(String slug) throws Exception {
        return ids(body(mvc.perform(get("/api/v1/topics/" + slug + "/posts")).andExpect(status().isOk())),
                "$.result[*].id");
    }

    private long topicId(String slug) {
        return jdbc.queryForObject("SELECT id FROM topics WHERE slug = ?", Long.class, slug);
    }

    private static List<Long> ids(String json, String path) {
        return JsonPath.<List<Number>>read(json, path).stream().map(Number::longValue).toList();
    }

    private long draft(Cookie cookie, String handle, String body) throws Exception {
        MvcResult result = json(post("/api/v1/blogs/" + handle + "/posts/drafts"), body, cookie)
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.id")).longValue();
    }

    private Cookie signup(String email, String handle) throws Exception {
        MvcResult result = json(post("/api/v1/auth/signup"), """
                {"email":"%s","password":"password123","nickname":"%s","handle":"%s",
                 "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06"}"""
                .formatted(email, handle, handle), null)
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

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
