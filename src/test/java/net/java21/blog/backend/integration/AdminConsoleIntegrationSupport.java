package net.java21.blog.backend.integration;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import net.java21.blog.backend.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 006 관리 화면 통합 시험(T008·T015·T045·T046)의 공통 구성: H2 위의 전체 컨텍스트 하나(같은 설정이라 컨텍스트 캐시를 함께 쓴다),
 * 가입·권한 바꾸기 도우미, Spring MVC 매핑 목록 읽기(research A8·A10). 시험끼리 같은 DB를 쓰므로 회원은 실행마다 다른 이메일로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:adminconsole;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.admin.dashboard-cache-ttl=0s",
        "blog.portal.cache-ttl=0s"
})
abstract class AdminConsoleIntegrationSupport {

    static final String ORIGIN = "http://localhost:5173";
    static final String PASSWORD = "password123";
    /** 경로 변수에 넣는 없는 번호(다른 시험의 데이터를 건드리지 않게 큰 값). */
    static final String MISSING_ID = "987654";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::((?:[^{}]|\\{[^}]*\\})*))?\\}");
    private static int sequence;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /** 회원 하나(가입 API). 블로그 {@code handle}이 함께 생긴다. */
    record Member(long id, String handle, Cookie cookie) {
    }

    /** 실행마다 다른 handle(영문 소문자·숫자, 20자 이하) */
    static synchronized String uniqueHandle(String prefix) {
        sequence++;
        String suffix = Long.toString(System.nanoTime() % 2_000_000_000L, 36) + sequence;
        return (prefix + suffix).substring(0, Math.min(20, prefix.length() + suffix.length()));
    }

    Member signup(String prefix) throws Exception {
        String handle = uniqueHandle(prefix);
        MvcResult result = mvc.perform(json(HttpMethod.POST, "/api/v1/auth/signup", """
                {"email":"%s@example.com","password":"%s","nickname":"%s","handle":"%s",
                 "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06"}"""
                .formatted(handle, PASSWORD, handle, handle), null))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.userId")).longValue();
        return new Member(id, handle, result.getResponse().getCookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE));
    }

    /** 회원 하나를 만들고 DB에서 바로 권한을 준다(토큰의 role은 USER 그대로 — 관리자 확인은 DB만 본다). */
    Member signupAs(String prefix, String role) throws Exception {
        Member member = signup(prefix);
        setRole(member, role);
        return member;
    }

    void setRole(Member member, String role) {
        jdbc.update("UPDATE users SET role = ? WHERE id = ?", role, member.id());
    }

    void setStatus(Member member, String status) {
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, member.id());
    }

    MockHttpServletRequestBuilder json(HttpMethod method, String path, String body, Cookie cookie) {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, path).header("Origin", ORIGIN);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (cookie != null) {
            builder.cookie(cookie);
        }
        return builder;
    }

    /** 요청을 보내고 응답(상태, 본문)을 돌려준다. */
    Reply send(HttpMethod method, String path, String body, Cookie cookie) throws Exception {
        MvcResult result = mvc.perform(json(method, path, body, cookie)).andReturn();
        return new Reply(result.getResponse().getStatus(),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    record Reply(int status, String body) {

        String resultCode() {
            return body.isEmpty() ? "" : JsonPath.read(body, "$.header.resultCode");
        }

        String resultMessage() {
            return body.isEmpty() ? "" : JsonPath.read(body, "$.header.resultMessage");
        }

        <T> T read(String path) {
            return JsonPath.read(body, path);
        }
    }

    /** 매핑 하나(메서드 × 경로 패턴). */
    record Endpoint(String method, String pattern) {

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    /** {@code prefix}로 시작하는 모든 MVC 매핑(메서드가 없으면 GET). */
    List<Endpoint> endpoints(String prefix) {
        Set<Endpoint> endpoints = new LinkedHashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPathPatternsCondition() == null ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!pattern.startsWith(prefix)) {
                    continue;
                }
                if (methods.isEmpty()) {
                    endpoints.add(new Endpoint("GET", pattern));
                }
                for (RequestMethod method : methods) {
                    endpoints.add(new Endpoint(method.name(), pattern));
                }
            }
        }
        List<Endpoint> sorted = new ArrayList<>(endpoints);
        sorted.sort((a, b) -> a.toString().compareTo(b.toString()));
        return sorted;
    }

    /**
     * 경로 변수를 채운다: {@code handle}은 주어진 값, {@code key}(설정 키)는 고정 문자열, {@code type}(005 콘텐츠 종류)은 {@code posts},
     * 나머지는 {@code id} 값.
     */
    static String fill(String pattern, String handle, String id) {
        Matcher matcher = PATH_VARIABLE.matcher(pattern);
        StringBuilder path = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = switch (name) {
                case "handle" -> handle;
                case "key" -> "portal.min-content-length";
                case "type" -> "posts";
                default -> id;
            };
            matcher.appendReplacement(path, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(path);
        return path.toString();
    }
}
