package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import net.java21.blog.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 핵심 흐름 1개(T067, quickstart #1~5): 가입 → 쿠키로 임시저장 → 발행 → 비로그인 {@code GET /posts/{id}}·{@code /blogs/{handle}/posts}에
 * 노출 → 비공개로 바꾸면 404. 전체 컨텍스트(보안 필터·Origin 검사·쿠키 인증·서비스·JPA)를 H2(MySQL 모드)로 띄워 외부 DB 없이 돈다.
 * 실제 스키마 매핑은 {@code EntitySchemaValidationTest}가 테스트 MySQL에서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:flowtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SignupToPublishFlowTest {

    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mvc;

    @Test
    void signupDraftPublishThenAnonymousReadsUntilMadePrivate() throws Exception {
        // 가입: 접근 토큰 쿠키를 받는다.
        MvcResult signup = mvc.perform(json(post("/api/v1/auth/signup"), """
                        {"email":"flow@example.com","password":"password123","nickname":"흐름","handle":"flow",
                         "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06"}"""))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie access = signup.getResponse().getCookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE);
        assertThat(access).isNotNull();

        // 임시저장 → 발행 전에는 비로그인에게 404, 주인에게는 보인다.
        MvcResult created = mvc.perform(json(post("/api/v1/blogs/flow/posts/drafts"), """
                        {"title":"첫 글","contentMarkdown":"# 안녕\\n\\n<script>alert(1)</script>\\n\\n```java\\nint a;\\n```"}""")
                        .cookie(access))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.result.id")).longValue();
        mvc.perform(get("/api/v1/posts/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/blogs/flow/posts/drafts/latest").cookie(access))
                .andExpect(jsonPath("$.result.id").value(id));

        // 발행
        mvc.perform(json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PUBLIC\"}").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.result.contentMarkdown").isNotEmpty());

        // 비로그인 상세·목록에 노출, 본문은 살균된 HTML
        mvc.perform(get("/api/v1/posts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.title").value("첫 글"))
                .andExpect(jsonPath("$.result.contentHtml").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("<h1>안녕</h1>"),
                        org.hamcrest.Matchers.containsString("<code class=\"language-java\">"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("script")))))
                .andExpect(jsonPath("$.result.contentMarkdown").value(nullValue()))
                .andExpect(jsonPath("$.result.author.nickname").value("흐름"));
        mvc.perform(get("/api/v1/blogs/flow/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.result[0].id").value(id));

        // 조회수: 방문자 쿠키 발급 후 같은 방문자는 한 번만
        MvcResult view = mvc.perform(post("/api/v1/posts/" + id + "/views").header("Origin", ORIGIN))
                .andExpect(status().isOk()).andReturn();
        Cookie visitor = view.getResponse().getCookie("visitor_id");
        assertThat(visitor).isNotNull();
        mvc.perform(post("/api/v1/posts/" + id + "/views").header("Origin", ORIGIN).cookie(visitor))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/posts/" + id)).andExpect(jsonPath("$.result.viewCount").value(1));

        // 수정은 사본에만, 비공개로 수정 발행하면 비로그인에게 404·목록에서 빠짐
        mvc.perform(json(put("/api/v1/posts/" + id + "/draft"), "{\"title\":\"고친 글\",\"contentMarkdown\":\"고침\"}")
                        .cookie(access))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/posts/" + id)).andExpect(jsonPath("$.result.title").value("첫 글"));
        mvc.perform(json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PRIVATE\"}").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.title").value("고친 글"));
        mvc.perform(get("/api/v1/posts/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"));
        mvc.perform(get("/api/v1/blogs/flow/posts")).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(get("/api/v1/posts/" + id).cookie(access)).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
