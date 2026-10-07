package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * 006 T045(US3 AS3, FR-105, Edge Cases): 권한 부여·회수가 토큰 재발급 없이 다음 관리자 API 요청부터 반영되고, 작업 기록과 관리자
 * 목록에 나타나며, 두 최고 관리자가 서로를 동시에 낮추면 하나만 성공하고 다른 하나는 409 {@code LAST_SUPER_ADMIN}이다.
 */
class AdminRoleIntegrationTest extends AdminConsoleIntegrationSupport {

    @Test
    void grantAndRevokeTakeEffectOnTheNextRequest() throws Exception {
        Member root = signupAs("arroot", "SUPER_ADMIN");
        Member member = signup("armember");

        assertThat(send(HttpMethod.GET, "/api/v1/admin/dashboard", null, member.cookie()).status()).isEqualTo(404);
        Reply granted = send(HttpMethod.PUT, "/api/v1/admin/users/" + member.id() + "/role", "{\"role\":\"ADMIN\"}",
                root.cookie());
        assertThat(granted.status()).as(granted.body()).isEqualTo(200);
        assertThat(granted.<String>read("$.result.role")).isEqualTo("ADMIN");
        assertThat(send(HttpMethod.GET, "/api/v1/admin/dashboard", null, member.cookie()).status())
                .as("같은 접근 토큰으로 바로").isEqualTo(200);

        Reply admins = send(HttpMethod.GET, "/api/v1/admin/admins", null, member.cookie());
        assertThat(admins.<List<Integer>>read("$.result[*].userId")).contains((int) member.id(), (int) root.id());
        Reply forbidden = send(HttpMethod.PUT, "/api/v1/admin/users/" + root.id() + "/role", "{\"role\":\"USER\"}",
                member.cookie());
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.resultCode()).isEqualTo("FORBIDDEN");

        Reply same = send(HttpMethod.PUT, "/api/v1/admin/users/" + member.id() + "/role", "{\"role\":\"ADMIN\"}",
                root.cookie());
        assertThat(same.status()).isEqualTo(200);
        Reply revoked = send(HttpMethod.PUT, "/api/v1/admin/users/" + member.id() + "/role", "{\"role\":\"USER\"}",
                root.cookie());
        assertThat(revoked.status()).isEqualTo(200);
        Reply hidden = send(HttpMethod.GET, "/api/v1/admin/dashboard", null, member.cookie());
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(hidden.resultCode()).isEqualTo("NOT_FOUND");

        Reply log = send(HttpMethod.GET, "/api/v1/admin/audit-logs?targetType=USER&targetId=" + member.id(), null,
                root.cookie());
        assertThat(log.<List<String>>read("$.result[*].action")).containsExactly("ROLE_REVOKE", "ROLE_GRANT");
        assertThat(log.<String>read("$.result[0].before.role")).isEqualTo("ADMIN");
        assertThat(log.<String>read("$.result[0].after.role")).isEqualTo("USER");

        Reply own = send(HttpMethod.PUT, "/api/v1/admin/users/" + root.id() + "/role", "{\"role\":\"USER\"}",
                root.cookie());
        assertThat(own.status()).isEqualTo(422);
        assertThat(own.resultCode()).isEqualTo("CANNOT_CHANGE_OWN_ROLE");
        Member suspended = signup("arsusp");
        setStatus(suspended, "SUSPENDED");
        assertThat(send(HttpMethod.PUT, "/api/v1/admin/users/" + suspended.id() + "/role", "{\"role\":\"ADMIN\"}",
                root.cookie()).resultCode()).isEqualTo("USER_NOT_ACTIVE");
        assertThat(send(HttpMethod.PUT, "/api/v1/admin/users/" + MISSING_ID + "/role", "{\"role\":\"ADMIN\"}",
                root.cookie()).resultCode()).isEqualTo("USER_NOT_FOUND");
        Reply invalid = send(HttpMethod.PUT, "/api/v1/admin/users/" + member.id() + "/role", "{\"role\":\"OWNER\"}",
                root.cookie());
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.<String>read("$.header.fieldErrors[0].code")).isEqualTo("INVALID");
    }

    @Test
    void mutualDemotionLeavesOneSuperAdmin() throws Exception {
        // 이 시험만의 최고 관리자 둘. 다른 시험의 최고 관리자는 잠시 USER로 내려 "마지막 둘"을 만든다.
        List<Long> others = jdbc.queryForList(
                "SELECT id FROM users WHERE role = 'SUPER_ADMIN' AND status = 'ACTIVE'", Long.class);
        others.forEach(id -> jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", id));
        try {
            Member a = signupAs("armuta", "SUPER_ADMIN");
            Member b = signupAs("armutb", "SUPER_ADMIN");
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Reply> ab = pool.submit(() -> {
                    start.await();
                    return send(HttpMethod.PUT, "/api/v1/admin/users/" + b.id() + "/role", "{\"role\":\"USER\"}",
                            a.cookie());
                });
                Future<Reply> ba = pool.submit(() -> {
                    start.await();
                    return send(HttpMethod.PUT, "/api/v1/admin/users/" + a.id() + "/role", "{\"role\":\"USER\"}",
                            b.cookie());
                });
                start.countDown();
                List<Reply> replies = new ArrayList<>(List.of(ab.get(30, TimeUnit.SECONDS),
                        ba.get(30, TimeUnit.SECONDS)));
                List<Integer> statuses = new ArrayList<>(replies.stream().map(Reply::status).toList());
                Collections.sort(statuses);
                assertThat(statuses).as(replies.toString()).containsExactly(200, 409);
                assertThat(replies.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().resultCode())
                        .isEqualTo("LAST_SUPER_ADMIN");
            } finally {
                pool.shutdownNow();
            }
            Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'SUPER_ADMIN' AND status = "
                    + "'ACTIVE' AND id IN (?, ?)", Integer.class, a.id(), b.id());
            assertThat(left).isEqualTo(1);
        } finally {
            others.forEach(id -> jdbc.update("UPDATE users SET role = 'SUPER_ADMIN' WHERE id = ?", id));
        }
    }
}
