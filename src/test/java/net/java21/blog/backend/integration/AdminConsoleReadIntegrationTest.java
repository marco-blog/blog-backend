package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import net.java21.blog.backend.blog.ReservedHandles;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * 006 US2(T024·T041) 통합: 대시보드(TTL 0이라 바로 반영), 예약어·서비스 설정(비밀 값 없음), 콘텐츠 검색, 작업 기록 목록·상세(요청 IP는
 * 최고 관리자만)·작업 목록을 실제 컨텍스트에서 확인한다.
 */
class AdminConsoleReadIntegrationTest extends AdminConsoleIntegrationSupport {

    @Test
    void dashboardAndInfo() throws Exception {
        Member admin = signupAs("crad", "ADMIN");
        Reply before = send(HttpMethod.GET, "/api/v1/admin/dashboard", null, admin.cookie());
        assertThat(before.status()).isEqualTo(200);
        int signups = before.read("$.result.today.signups");
        signup("crnew");
        Reply after = send(HttpMethod.GET, "/api/v1/admin/dashboard", null, admin.cookie());
        assertThat(after.<Integer>read("$.result.today.signups")).isEqualTo(signups + 1);
        assertThat(after.<List<Object>>read("$.result.trend")).hasSize(7);
        assertThat(after.<String>read("$.result.timeZone")).isNotBlank();
        assertThat(after.body()).contains("\"pendingReports\":null");

        Reply handles = send(HttpMethod.GET, "/api/v1/admin/reserved-handles", null, admin.cookie());
        assertThat(handles.<List<String>>read("$.result")).hasSize(ReservedHandles.NAMES.size()).isSorted()
                .contains("admin");
        Reply settings = send(HttpMethod.GET, "/api/v1/admin/service-settings", null, admin.cookie());
        assertThat(settings.status()).isEqualTo(200);
        assertThat(settings.<String>read("$.result.admin.dashboardCacheTtl")).isEqualTo("PT0S");
        assertThat(settings.<String>read("$.result.admin.auditRetention")).isEqualTo("PT8760H");
        assertThat(settings.<Integer>read("$.result.blogs.defaultMaxPerMember")).isPositive();
        assertThat(settings.<List<String>>read("$.result.media.allowedTypes")).isNotEmpty();
        assertThat(settings.body().toLowerCase()).doesNotContain("secret", "password", "jwt", "key\"");
    }

    @Test
    void contentSearchAndAuditLog() throws Exception {
        Member root = signupAs("crroot", "SUPER_ADMIN");
        Member admin = signupAs("cradm", "ADMIN");
        Member writer = signup("crwrite");
        Reply draft = send(HttpMethod.POST, "/api/v1/blogs/" + writer.handle() + "/posts/drafts",
                "{\"title\":\"관리 검색\",\"contentMarkdown\":\"본문\",\"tags\":[]}", writer.cookie());
        long postId = ((Number) draft.read("$.result.id")).longValue();

        Reply posts = send(HttpMethod.GET, "/api/v1/admin/contents/posts?handle=" + writer.handle().toUpperCase(),
                null, admin.cookie());
        assertThat(posts.<List<Integer>>read("$.result[*].id")).containsExactly((int) postId);
        assertThat(posts.<String>read("$.result[0].status")).isEqualTo("DRAFT");
        assertThat(posts.body()).doesNotContain("contentMarkdown", "본문");
        Reply scope = send(HttpMethod.GET, "/api/v1/admin/contents/comments?q=" + "abc", null, admin.cookie());
        assertThat(scope.status()).isEqualTo(400);
        assertThat(scope.<String>read("$.header.fieldErrors[0].params.reason")).isEqualTo("SCOPE_REQUIRED");
        assertThat(send(HttpMethod.GET, "/api/v1/admin/contents/guestbook-entries?handle=" + writer.handle(), null,
                admin.cookie()).status()).isEqualTo(200);

        send(HttpMethod.PATCH, "/api/v1/admin/users/" + writer.id() + "/blog-limit", "{\"maxBlogs\":2}",
                root.cookie());
        Reply list = send(HttpMethod.GET, "/api/v1/admin/audit-logs?adminId=" + root.id()
                + "&action=USER_BLOG_LIMIT_CHANGE", null, admin.cookie());
        assertThat(list.<Integer>read("$.totalCount")).isEqualTo(1);
        long logId = ((Number) list.read("$.result[0].id")).longValue();
        assertThat(list.<Integer>read("$.result[0].after.maxBlogs")).isEqualTo(2);
        assertThat(send(HttpMethod.GET, "/api/v1/admin/audit-logs/" + logId, null, admin.cookie())
                .<Object>read("$.result.requestIp")).isNull();
        assertThat(send(HttpMethod.GET, "/api/v1/admin/audit-logs/" + logId, null, root.cookie())
                .<String>read("$.result.requestIp")).isEqualTo("127.0.0.1");
        assertThat(send(HttpMethod.GET, "/api/v1/admin/audit-logs/" + MISSING_ID, null, root.cookie()).resultCode())
                .isEqualTo("NOT_FOUND");
        assertThat(send(HttpMethod.GET, "/api/v1/admin/audit-logs/actions", null, admin.cookie())
                .<List<String>>read("$.result.actions")).contains("ROLE_GRANT", "USER_BLOG_LIMIT_CHANGE");
        Reply tooLong = send(HttpMethod.GET, "/api/v1/admin/audit-logs?from=2024-01-01&to=2026-01-01", null,
                admin.cookie());
        assertThat(tooLong.<Integer>read("$.header.fieldErrors[0].params.maxDays")).isEqualTo(366);
    }
}
