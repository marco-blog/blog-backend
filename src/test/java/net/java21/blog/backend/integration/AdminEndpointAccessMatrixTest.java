package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * 006 T008(SC-015, US2 AS2, FR-097, research A10): {@code /api/v1/admin/**}의 모든 MVC 매핑을 비로그인·일반 회원·정지된 관리자·
 * 권한이 회수된 관리자(유효한 토큰)로 부르면 모두 404 {@code NOT_FOUND}(필터의 공통 틀, 관리자 기능 흔적 없음)이고, 최고 관리자로 부르면
 * 필터를 통과해 컨트롤러가 답한다. 매핑은 실행 때 읽으므로 003·005·007이 더한 관리자 API도 자동으로 덮는다.
 */
class AdminEndpointAccessMatrixTest extends AdminConsoleIntegrationSupport {

    /** {@code AdminAccessFilter}가 쓰는 메시지(컨트롤러의 404 메시지와 구별한다). */
    private static final String FILTER_MESSAGE = "Not found";

    @Test
    void everyAdminEndpointIsHiddenFromNonAdmins() throws Exception {
        List<Endpoint> endpoints = endpoints("/api/v1/admin/");
        assertThat(endpoints).as("관리자 매핑이 하나도 없으면 경로 오타").isNotEmpty();

        Member member = signup("amuser");
        Member suspended = signupAs("amsusp", "ADMIN");
        setStatus(suspended, "SUSPENDED");
        Member revoked = signupAs("amrev", "SUPER_ADMIN");
        setRole(revoked, "USER");
        Member root = signupAs("amroot", "SUPER_ADMIN");

        Map<String, Cookie> outsiders = new LinkedHashMap<>();
        outsiders.put("비로그인", null);
        outsiders.put("일반 회원", member.cookie());
        outsiders.put("정지된 관리자", suspended.cookie());
        outsiders.put("권한이 회수된 관리자", revoked.cookie());

        List<String> leaks = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            String path = fill(endpoint.pattern(), root.handle(), MISSING_ID);
            HttpMethod method = HttpMethod.valueOf(endpoint.method());
            String body = method == HttpMethod.GET ? null : "{}";
            for (Map.Entry<String, Cookie> outsider : outsiders.entrySet()) {
                Reply reply = send(method, path, body, outsider.getValue());
                boolean hidden = reply.status() == 404 && "NOT_FOUND".equals(reply.resultCode())
                        && FILTER_MESSAGE.equals(reply.resultMessage())
                        && reply.body().contains("\"result\":null");
                if (!hidden) {
                    leaks.add(outsider.getKey() + " " + endpoint + " -> " + reply.status() + " " + reply.body());
                }
            }
            Reply admin = send(method, path, body, root.cookie());
            if (admin.status() == 404 && "NOT_FOUND".equals(admin.resultCode())
                    && FILTER_MESSAGE.equals(admin.resultMessage())) {
                blocked.add(endpoint.toString());
            }
        }
        assertThat(leaks).as("관리자가 아닌데 404 NOT_FOUND가 아닌 응답(SC-015)").isEmpty();
        assertThat(blocked).as("최고 관리자인데 필터에서 막힘").isEmpty();
    }

    @Test
    void superAdminOnlyEndpointsAnswerForbiddenToAdmins() throws Exception {
        Member admin = signupAs("amadm", "ADMIN");
        Member target = signup("amtgt");

        Reply reply = send(HttpMethod.PUT, "/api/v1/admin/users/" + target.id() + "/role", "{\"role\":\"ADMIN\"}",
                admin.cookie());

        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.resultCode()).isEqualTo("FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, target.id()))
                .isEqualTo("USER");
    }
}
