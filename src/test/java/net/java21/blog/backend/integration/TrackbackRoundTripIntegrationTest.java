package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 005 T106(SC-009 송신): 실제 HTTP로 트랙백을 주고받는다. 서버를 실제 포트로 띄우고, B 글을 발행하면서 A 글의 트랙백 주소
 * ({@code http://localhost:{port}/{a}/{id}/trackback})로 보내면 보낸 기록이 SUCCESS가 되고 A 글에 트랙백 1건이 생긴다. 같은 주소로
 * 다시 보내면(발행된 글 수정) 받는 쪽이 {@code Duplicate trackback}으로 거절해 FAILED {@code REMOTE_ERROR}가 된다.
 * <p>{@code blog.base-url}을 다른 호스트로 두어 서비스 안 글 경로(HTTP 없이 바로 저장)를 타지 않게 하고, 루프백으로 보내도록
 * {@code blog.outbound.allow-private=true}로 둔다. 보내는 쪽의 포트 허용 목록({@code blog.outbound.allowed-ports})에 서버 포트가
 * 들어가야 하므로 {@code RANDOM_PORT} 대신 미리 고른 빈 포트로 띄운다(포트는 실행마다 다르다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:trackbackroundtrip;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.base-url=https://blog.example.com",
        "blog.outbound.allow-private=true"
})
class TrackbackRoundTripIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "password123";
    private static final int PORT = freePort();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void port(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("blog.outbound.allowed-ports", () -> "80,443," + PORT);
    }

    @Test
    void publishingSendsATrackbackOverHttpAndResendingIsRejectedAsDuplicate() throws Exception {
        Cookie a = signup("tb-a@example.com", "tbreceiver");
        Cookie b = signup("tb-b@example.com", "tbsender");
        long target = publish(a, "tbreceiver", "받는 글", "{\"visibility\":\"PUBLIC\"}");
        String trackbackUrl = "http://localhost:" + PORT + "/tbreceiver/" + target + "/trackback";

        long source = publish(b, "tbsender", "보내는 글",
                "{\"visibility\":\"PUBLIC\",\"trackbackUrls\":[\"" + trackbackUrl + "\"]}");

        List<Map<String, Object>> pings = awaitSettled(source, b, 1);
        assertThat(pings).singleElement().satisfies(ping -> {
            assertThat(ping.get("targetUrl")).isEqualTo(trackbackUrl);
            assertThat(ping.get("status")).isEqualTo("SUCCESS");
            assertThat(ping.get("errorCode")).isNull();
        });
        String received = body(mvc.perform(get("/api/v1/posts/" + target + "/trackbacks"))
                .andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<Integer>read(received, "$.totalCount")).isEqualTo(1);
        assertThat(JsonPath.<String>read(received, "$.result[0].url"))
                .isEqualTo("https://blog.example.com/tbsender/" + source);
        assertThat(JsonPath.<String>read(received, "$.result[0].title")).isEqualTo("보내는 글");
        assertThat(JsonPath.<Boolean>read(received, "$.result[0].internal")).isFalse();

        // 같은 주소로 다시 보내기(발행된 글의 발행 설정 저장)
        json(post("/api/v1/posts/" + source + "/publish"),
                "{\"visibility\":\"PUBLIC\",\"trackbackUrls\":[\"" + trackbackUrl + "\"]}", b)
                .andExpect(status().isOk());
        List<Map<String, Object>> after = awaitSettled(source, b, 2);
        assertThat(after).filteredOn(ping -> "FAILED".equals(ping.get("status"))).singleElement()
                .satisfies(ping -> {
                    assertThat(ping.get("errorCode")).isEqualTo("REMOTE_ERROR");
                    assertThat(ping.get("errorMessage")).isEqualTo("Duplicate trackback");
                });
        assertThat(JsonPath.<Integer>read(body(mvc.perform(get("/api/v1/posts/" + target + "/trackbacks"))
                .andReturn()), "$.totalCount")).isEqualTo(1);
    }

    /** 보낸 기록이 {@code count}건 모두 PENDING이 아니게 될 때까지(최대 20초) 기다린다. */
    private List<Map<String, Object>> awaitSettled(long postId, Cookie cookie, int count) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        List<Map<String, Object>> pings = List.of();
        while (Instant.now().isBefore(deadline)) {
            String json = body(mvc.perform(get("/api/v1/posts/" + postId + "/trackback-pings").cookie(cookie))
                    .andExpect(status().isOk()).andReturn());
            pings = JsonPath.read(json, "$.result");
            if (pings.size() == count && pings.stream().noneMatch(ping -> "PENDING".equals(ping.get("status")))) {
                return pings;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("트랙백 보내기가 끝나지 않음: " + pings);
    }

    private long publish(Cookie cookie, String handle, String title, String settings) throws Exception {
        MvcResult draft = json(post("/api/v1/blogs/" + handle + "/posts/drafts"), "{\"title\":\"" + title
                + "\",\"contentMarkdown\":\"" + "트랙백 본문 ".repeat(10) + "\",\"tags\":[]}", cookie)
                .andExpect(status().isCreated()).andReturn();
        long id = ((Number) JsonPath.read(draft.getResponse().getContentAsString(), "$.result.id")).longValue();
        json(post("/api/v1/posts/" + id + "/publish"), settings, cookie).andExpect(status().isOk());
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

    private ResultActions json(MockHttpServletRequestBuilder builder, String body, Cookie cookie) throws Exception {
        builder.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookie != null) {
            builder.cookie(cookie);
        }
        return mvc.perform(builder);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
