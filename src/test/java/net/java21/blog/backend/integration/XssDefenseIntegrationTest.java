package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import net.java21.blog.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 저장 시 XSS 방어(T239, research R27 1층, FR-021·FR-156). 악성 본문을 실제 API로 임시저장·발행·수정 발행한 뒤
 * 독자가 받는 {@code contentHtml}·{@code summary}에서 {@code <script>}, {@code on*} 속성, {@code javascript:} 주소,
 * 허용 목록(YouTube·Vimeo) 밖 iframe이 모두 사라지고, 허용된 iframe은 남는지 본다. SVG·HTML을 이미지로 올리면
 * 415로 거부되고 아무것도 저장되지 않는다. 전체 컨텍스트를 H2(MySQL 모드)와 {@code @TempDir} 이미지 디렉터리로 띄운다.
 * CSP 등 브라우저 헤더(R27 3층)는 HTML을 내려주는 front 서버의 일이라 여기서 보지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:xssdefense;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class XssDefenseIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";

    private static final String MALICIOUS = """
            # 제목

            <script>alert('script')</script>

            <img src="/media/k3Jd9fQ2xLmA7pZ0bR5tYw" onerror="alert('img')" alt="그림">

            [링크](javascript:alert('md')) <a href="JaVaScRiPt:alert('a')" onclick="alert('click')">a</a>

            <iframe src="https://evil.example.com/embed/dQw4w9WgXcQ"></iframe>

            <iframe src="javascript:alert('frame')"></iframe>

            <iframe srcdoc="<script>alert('srcdoc')</script>"></iframe>

            <iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ" onload="alert('yt')"></iframe>

            <p style="background:url(javascript:alert('css'))">끝</p>
            """;

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void mediaDirs(DynamicPropertyRegistry registry) {
        registry.add("blog.media.upload-dir", () -> root.resolve("upload").toString());
        registry.add("blog.media.temp-dir", () -> root.resolve("temp").toString());
        registry.add("blog.media.thumbnail-dir", () -> root.resolve("thumb").toString());
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void maliciousMarkdownIsNeutralizedOnPublishAndRepublish() throws Exception {
        Cookie writer = signup("xss@example.com", "xss");

        long id = id(json(post("/api/v1/blogs/xss/posts/drafts"),
                "{\"title\":\"악성 본문\",\"contentMarkdown\":" + quote(MALICIOUS) + "}", writer)
                .andExpect(status().isCreated()).andReturn());
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PUBLIC\"}", writer)
                .andExpect(status().isOk());
        assertNeutralized(id);

        // 수정 발행(작성 중 사본 → 발행본)도 같은 살균을 거친다.
        json(put("/api/v1/posts/" + id + "/draft"), "{\"title\":\"고침\",\"contentMarkdown\":"
                + quote(MALICIOUS + "\n<svg onload=\"alert('svg')\"><script>alert(1)</script></svg>") + "}", writer)
                .andExpect(status().isOk());
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PUBLIC\"}", writer)
                .andExpect(status().isOk());
        String html = assertNeutralized(id);
        assertThat(html).doesNotContainIgnoringCase("<svg");

        // DB에 저장된 발행본 자체가 살균돼 있다(SSR·피드가 같은 값을 쓴다).
        String stored = jdbc.queryForObject("SELECT content_html FROM posts WHERE id = ?", String.class, id);
        assertThat(stored).isEqualTo(html);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "evil.svg|image/svg+xml|<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"><script>alert(2)</script></svg>",
            "evil.png|image/png|<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
            "evil.jpg|image/jpeg|<!DOCTYPE html><html><body><img src=x onerror=alert(1)></body></html>"
    })
    void svgAndHtmlUploadsAreRejected(String filename, String contentType, String content) throws Exception {
        Cookie uploader = signup(filename + "@example.com", "up" + Math.abs(filename.hashCode() % 100000));
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM media", Integer.class);
        mvc.perform(multipart("/api/v1/media")
                        .file(new MockMultipartFile("file", filename, contentType,
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header("Origin", ORIGIN).cookie(uploader))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_TYPE_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media", Integer.class)).isEqualTo(before);
    }

    /** 독자(비로그인)가 받는 상세·목록에서 위험 요소가 없고 허용된 것만 남았는지 확인하고 본문 HTML을 돌려준다. */
    private String assertNeutralized(long id) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/posts/" + id)).andExpect(status().isOk()).andReturn();
        String body = detail.getResponse().getContentAsString();
        String html = JsonPath.read(body, "$.result.contentHtml");
        String summary = JsonPath.read(body, "$.result.summary");

        assertThat(html)
                .doesNotContainIgnoringCase("<script")
                .doesNotContainIgnoringCase("onerror")
                .doesNotContainIgnoringCase("onclick")
                .doesNotContainIgnoringCase("onload")
                .doesNotContainIgnoringCase("javascript:")
                .doesNotContainIgnoringCase("srcdoc")
                .doesNotContainIgnoringCase("style=")
                .doesNotContain("evil.example.com")
                .doesNotContain("alert(");
        // 허용된 것은 남는다: 제목, 이미지(속성만 제거), 링크 글자, YouTube iframe 하나
        assertThat(html).contains("<h1>제목</h1>")
                .contains("<img src=\"/media/k3Jd9fQ2xLmA7pZ0bR5tYw\" alt=\"그림\"")
                .contains("<iframe src=\"https://www.youtube.com/embed/dQw4w9WgXcQ\"");
        assertThat(html.split("<iframe", -1)).hasSize(2);
        assertThat(summary).doesNotContain("<").doesNotContainIgnoringCase("javascript:");

        String list = mvc.perform(get("/api/v1/blogs/xss/posts")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContainIgnoringCase("<script").doesNotContainIgnoringCase("javascript:");
        return html;
    }

    private Cookie signup(String email, String handle) throws Exception {
        MvcResult signup = json(post("/api/v1/auth/signup"), """
                {"email":"%s","password":"password123","nickname":"%s","handle":"%s",
                 "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06"}"""
                .formatted(email, handle, handle), null)
                .andExpect(status().isCreated())
                .andReturn();
        return signup.getResponse().getCookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE);
    }

    private ResultActions json(MockHttpServletRequestBuilder builder, String body, Cookie cookie) throws Exception {
        builder.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookie != null) {
            builder.cookie(cookie);
        }
        return mvc.perform(builder);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.id")).longValue();
    }
}
