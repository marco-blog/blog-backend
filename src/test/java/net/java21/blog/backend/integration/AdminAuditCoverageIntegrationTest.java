package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.audit.AuditActions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * 006 T046(SC-017, US3 AS1, FR-106, research A8): {@code /api/v1/admin/**}의 GET이 아닌 매핑 전체가 아래 표에 있어야 하고, 표의 각
 * 행을 최고 관리자로 보내면 2xx 뒤 {@code admin_audit_logs}에 기대한 {@code action} 행이 하나 늘어야 한다.
 * <p><b>새 관리자 변경 API를 더하면 이 표에 행을 더한다</b>(005·007 포함). 상태를 바꾸지 않는 요청은 {@link #NO_AUDIT}에 이유와 함께 둔다.
 */
class AdminAuditCoverageIntegrationTest extends AdminConsoleIntegrationSupport {

    private static final String LONG_TEXT = "포털 노출 기준을 넘는 충분히 긴 본문입니다. ".repeat(30);

    /** 상태를 바꾸지 않아 기록하지 않는 매핑과 그 이유. */
    private static final Map<String, String> NO_AUDIT = Map.of(
            "POST /api/v1/admin/release-notes/preview", "Markdown 변환 결과만 돌려준다(저장 없음)",
            "PATCH /api/v1/admin/reports/{id}/target",
            "권리 침해 신고에 대상 콘텐츠를 연결만 한다. 처리 결정은 resolve가 REPORT_ACTION·REPORT_DISMISS로 남긴다"
                    + "(005 data-model에 이 작업의 action 코드가 없음 — marco 확인 대기)");

    /** 한 행의 실제 요청(경로 변수 채움, 본문). */
    private record Call(String path, String body) {
    }

    @FunctionalInterface
    private interface Prepare {
        Call prepare() throws Exception;
    }

    /** 표의 행: 매핑(메서드 + 패턴) → 준비·요청 → 기대 action. */
    private record Row(String method, String pattern, String action, Prepare prepare) {

        String key() {
            return method + " " + pattern;
        }
    }

    private Member root;
    private Member writer;
    private long post;
    private long topicParent;
    private final List<Long> topicChildren = new ArrayList<>();
    private long curation;
    private Member suspendTarget;
    private Member reporter;
    private long note;
    private long draftNote;

    @Test
    void everyAdminChangeIsAudited() throws Exception {
        root = signupAs("acroot", "SUPER_ADMIN");
        writer = signup("acwriter");
        jdbc.update("UPDATE users SET created_at = DATEADD('DAY', -3, CURRENT_TIMESTAMP) WHERE id = ?", writer.id());
        post = publish(writer);

        List<Row> rows = table();
        Map<String, Row> byKey = new LinkedHashMap<>();
        rows.forEach(row -> byKey.put(row.key(), row));

        List<String> missing = new ArrayList<>();
        for (Endpoint endpoint : endpoints("/api/v1/admin/")) {
            if (endpoint.method().equals("GET") || endpoint.method().equals("HEAD")
                    || endpoint.method().equals("OPTIONS")) {
                continue;
            }
            if (!byKey.containsKey(endpoint.toString()) && !NO_AUDIT.containsKey(endpoint.toString())) {
                missing.add(endpoint.toString());
            }
        }
        assertThat(missing).as("새 관리자 변경 API는 이 표에 행을 더한다").isEmpty();
        assertThat(AuditActions.ALL).containsAll(rows.stream().map(Row::action).toList());

        List<String> failures = new ArrayList<>();
        for (Row row : rows) {
            Call call = row.prepare().prepare();
            int before = count(row.action());
            Reply reply = send(HttpMethod.valueOf(row.method()), call.path(), call.body(), root.cookie());
            if (reply.status() / 100 != 2) {
                failures.add(row.key() + " -> " + reply.status() + " " + reply.body());
                continue;
            }
            after(row, reply);
            if (count(row.action()) != before + 1) {
                failures.add(row.key() + " did not record " + row.action());
            }
        }
        assertThat(failures).isEmpty();

        int previews = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Integer.class);
        assertThat(send(HttpMethod.POST, "/api/v1/admin/release-notes/preview", "{\"contentMarkdown\":\"## 미리\"}",
                root.cookie()).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Integer.class)).isEqualTo(previews);
    }

    private List<Row> table() {
        String names = "{\"ko\":\"주제%1$s\",\"en\":\"Topic%1$s\",\"ja\":\"トピック%1$s\",\"zh-CN\":\"主题%1$s\"}";
        String version = (900 + (System.nanoTime() % 90)) + "." + (System.nanoTime() % 1000) + ".";
        return List.of(
                // 003 주제
                new Row("POST", "/api/v1/admin/topics", AuditActions.TOPIC_CREATE, () -> new Call(
                        "/api/v1/admin/topics", "{\"slug\":\"%s\",\"names\":%s,\"cardColor\":\"#3D7DD8\"}"
                                .formatted(uniqueHandle("ac-t"), names.formatted("A")))),
                new Row("PATCH", "/api/v1/admin/topics/{id}", AuditActions.TOPIC_UPDATE, () -> new Call(
                        "/api/v1/admin/topics/" + topicParent, "{\"cardColor\":\"#112233\"}")),
                new Row("PUT", "/api/v1/admin/topics/order", AuditActions.TOPIC_REORDER, () -> {
                    for (int i = 0; i < 2; i++) {
                        Reply child = send(HttpMethod.POST, "/api/v1/admin/topics", "{\"parentId\":%d,\"slug\":\"%s\",\"names\":%s}"
                                .formatted(topicParent, uniqueHandle("ac-c"), names.formatted("C" + i)), root.cookie());
                        topicChildren.add(((Number) child.read("$.result.id")).longValue());
                    }
                    return new Call("/api/v1/admin/topics/order", "{\"parentId\":%d,\"ids\":[%d,%d]}"
                            .formatted(topicParent, topicChildren.get(1), topicChildren.get(0)));
                }),
                // 003 포털 추천·제외
                new Row("POST", "/api/v1/admin/portal/curations", AuditActions.CURATION_CREATE, () -> new Call(
                        "/api/v1/admin/portal/curations", "{\"postId\":%d,\"startsAt\":\"2091-01-01T00:00:00Z\","
                                .formatted(post) + "\"endsAt\":\"2091-02-01T00:00:00Z\"}")),
                new Row("PATCH", "/api/v1/admin/portal/curations/{id}", AuditActions.CURATION_UPDATE, () -> new Call(
                        "/api/v1/admin/portal/curations/" + curation, "{\"sortOrder\":3}")),
                new Row("DELETE", "/api/v1/admin/portal/curations/{id}", AuditActions.CURATION_DELETE, () -> new Call(
                        "/api/v1/admin/portal/curations/" + curation, null)),
                new Row("PUT", "/api/v1/admin/portal/exclusions/{postId}", AuditActions.PORTAL_EXCLUDE, () -> new Call(
                        "/api/v1/admin/portal/exclusions/" + post, "{\"reason\":\"광고\"}")),
                new Row("DELETE", "/api/v1/admin/portal/exclusions/{postId}", AuditActions.PORTAL_UNEXCLUDE,
                        () -> new Call("/api/v1/admin/portal/exclusions/" + post, null)),
                // 003 설정
                new Row("PUT", "/api/v1/admin/settings/{key}", AuditActions.SETTING_CHANGE, () -> new Call(
                        "/api/v1/admin/settings/portal.topic-auto-hide-threshold", "{\"value\":7}")),
                new Row("DELETE", "/api/v1/admin/settings/{key}", AuditActions.SETTING_CHANGE, () -> new Call(
                        "/api/v1/admin/settings/portal.topic-auto-hide-threshold", null)),
                // 001·003 블로그 한도
                new Row("PATCH", "/api/v1/admin/users/{id}/blog-limit", AuditActions.USER_BLOG_LIMIT_CHANGE,
                        () -> new Call("/api/v1/admin/users/" + writer.id() + "/blog-limit", "{\"maxBlogs\":3}")),
                // 003 릴리스 노트
                new Row("POST", "/api/v1/admin/release-notes", AuditActions.RELEASE_NOTE_CREATE, () -> new Call(
                        "/api/v1/admin/release-notes", noteBody(version + "1", null))),
                new Row("PUT", "/api/v1/admin/release-notes/{id:\\d+}", AuditActions.RELEASE_NOTE_UPDATE,
                        () -> new Call("/api/v1/admin/release-notes/" + note, noteBody(version + "1", 1))),
                new Row("POST", "/api/v1/admin/release-notes/{id:\\d+}/publish", AuditActions.RELEASE_NOTE_PUBLISH,
                        () -> new Call("/api/v1/admin/release-notes/" + note + "/publish", null)),
                new Row("POST", "/api/v1/admin/release-notes/{id:\\d+}/unpublish",
                        AuditActions.RELEASE_NOTE_UNPUBLISH,
                        () -> new Call("/api/v1/admin/release-notes/" + note + "/unpublish", null)),
                new Row("DELETE", "/api/v1/admin/release-notes/{id:\\d+}", AuditActions.RELEASE_NOTE_DELETE, () -> {
                    Reply created = send(HttpMethod.POST, "/api/v1/admin/release-notes", noteBody(version + "2", null),
                            root.cookie());
                    draftNote = ((Number) created.read("$.result.id")).longValue();
                    return new Call("/api/v1/admin/release-notes/" + draftNote, null);
                }),
                // 005 회원 정지·해제, 콘텐츠 숨김·해제, 신고 처리
                new Row("POST", "/api/v1/admin/users/{id}/suspend", AuditActions.USER_SUSPEND, () -> {
                    suspendTarget = signup("acsusp");
                    return new Call("/api/v1/admin/users/" + suspendTarget.id() + "/suspend", "{\"reason\":\"스팸\"}");
                }),
                new Row("POST", "/api/v1/admin/users/{id}/unsuspend", AuditActions.USER_UNSUSPEND, () -> new Call(
                        "/api/v1/admin/users/" + suspendTarget.id() + "/unsuspend", "{}")),
                new Row("POST", "/api/v1/admin/reports/{id}/resolve", AuditActions.REPORT_DISMISS, () -> {
                    reporter = signup("acrep");
                    Reply report = send(HttpMethod.POST, "/api/v1/reports",
                            "{\"targetType\":\"POST\",\"targetId\":%d,\"reason\":\"SPAM\"}".formatted(post),
                            reporter.cookie());
                    assertThat(report.status()).as(report.body()).isEqualTo(201);
                    long reportId = ((Number) report.read("$.result.id")).longValue();
                    return new Call("/api/v1/admin/reports/" + reportId + "/resolve", "{\"decision\":\"DISMISS\"}");
                }),
                new Row("PUT", "/api/v1/admin/contents/{segment}/{id}/hidden", AuditActions.CONTENT_HIDE,
                        () -> new Call("/api/v1/admin/contents/posts/" + post + "/hidden", "{\"reason\":\"광고\"}")),
                new Row("DELETE", "/api/v1/admin/contents/{segment}/{id}/hidden", AuditActions.CONTENT_UNHIDE,
                        () -> new Call("/api/v1/admin/contents/posts/" + post + "/hidden", "{}")),
                // 006 관리자 권한
                new Row("PUT", "/api/v1/admin/users/{id}/role", AuditActions.ROLE_GRANT, () -> new Call(
                        "/api/v1/admin/users/" + writer.id() + "/role", "{\"role\":\"ADMIN\"}")));
    }

    /** 만든 자원의 id를 다음 행이 쓰도록 남긴다. */
    private void after(Row row, Reply reply) {
        switch (row.key()) {
            case "POST /api/v1/admin/topics" -> topicParent = ((Number) reply.read("$.result.id")).longValue();
            case "POST /api/v1/admin/portal/curations" -> curation = ((Number) reply.read("$.result.id")).longValue();
            case "POST /api/v1/admin/release-notes" -> note = ((Number) reply.read("$.result.id")).longValue();
            default -> {
            }
        }
    }

    private static String noteBody(String version, Integer baseRevisionNo) {
        String base = baseRevisionNo == null ? "" : ",\"baseRevisionNo\":" + baseRevisionNo;
        return "{\"version\":\"" + version + "\",\"releaseDate\":\"2026-10-06\",\"contents\":{\"ko\":{\"title\":\"노트\","
                + "\"contentMarkdown\":\"## 변경 " + (baseRevisionNo == null ? "처음" : "고침") + "\"}}" + base + "}";
    }

    private int count(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action = ?", Integer.class, action);
    }

    private long publish(Member member) throws Exception {
        Reply draft = send(HttpMethod.POST, "/api/v1/blogs/" + member.handle() + "/posts/drafts",
                "{\"title\":\"추천할 글\",\"contentMarkdown\":\"" + LONG_TEXT + "\",\"tags\":[]}", member.cookie());
        assertThat(draft.status()).as(draft.body()).isEqualTo(201);
        long id = ((Number) draft.read("$.result.id")).longValue();
        Reply published = send(HttpMethod.POST, "/api/v1/posts/" + id + "/publish", "{\"visibility\":\"PUBLIC\"}",
                member.cookie());
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        return id;
    }
}
