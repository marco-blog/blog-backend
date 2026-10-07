package net.java21.blog.backend.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * springdoc OpenAPI({@code /v3/api-docs})가 001 계약(blog-docs {@code specs/001-blog-core/contracts/api.md})과 맞는지 확인한다(T240,
 * api-guidelines 9절). 001 계약의 릴리스 노트·릴리스 노트 관리 절은 003이 구현하므로(001 결정 #8, 003 T127) 003 관리자 API와 함께 넣었다.
 * <ul>
 *   <li>계약의 모든 엔드포인트(메서드·경로)가 문서에 있다. 경로 변수 이름은 비교하지 않는다({@code {id}}·{@code {postId}}).</li>
 *   <li>이미지 원본·썸네일({@code /media/**}, 바이너리) 외 모든 응답 스키마가 공통 틀 {@code { header, result, totalCount?, nextCursor? }}이고
 *       {@code header}가 {@code isSuccessful·resultCode·resultMessage}를 가진다(api-guidelines 4절). 오류 응답 포함.</li>
 * </ul>
 * 운영(prod)에서는 문서를 끈다({@code application-prod.yml}).
 * 계약에 엔드포인트를 더하거나 빼면 {@link #CONTRACT}도 함께 고친다. 컨텍스트는 {@code SignupToPublishFlowTest}와 같은 구성이라 재사용된다.
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
class OpenApiContractTest {

    /** contracts/api.md의 001 엔드포인트(접두어 포함 전체 경로). */
    static final List<String> CONTRACT = List.of(
            // 인증
            "POST /api/v1/auth/signup",
            "GET /api/v1/auth/handle-availability",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/password-reset/request",
            "POST /api/v1/auth/password-reset/confirm",
            // 회원·계정 설정
            "GET /api/v1/me",
            "PATCH /api/v1/me",
            "DELETE /api/v1/me",
            "PUT /api/v1/me/password",
            "GET /api/v1/me/login-history",
            // 블로그
            "GET /api/v1/me/blogs",
            "POST /api/v1/blogs",
            "DELETE /api/v1/blogs/{handle}",
            "GET /api/v1/blogs/{handle}",
            "PATCH /api/v1/blogs/{handle}",
            "GET /api/v1/blogs/{handle}/posts",
            "GET /api/v1/blogs/{handle}/manage/posts",
            // 글
            "POST /api/v1/blogs/{handle}/posts/drafts",
            "PUT /api/v1/posts/{id}/draft",
            "GET /api/v1/blogs/{handle}/posts/drafts/latest",
            "GET /api/v1/posts/{id}/draft",
            "DELETE /api/v1/posts/{id}/draft",
            "POST /api/v1/posts/{id}/publish",
            "GET /api/v1/posts/{id}",
            "DELETE /api/v1/posts/{id}",
            "POST /api/v1/posts/{id}/restore",
            "POST /api/v1/posts/{id}/views",
            // 블로그 관리 뼈대
            "GET /api/v1/blogs/{handle}/manage/dashboard",
            "POST /api/v1/blogs/{handle}/manage/posts/bulk",
            "GET /api/v1/blogs/{handle}/manage/comments",
            // 카테고리
            "GET /api/v1/blogs/{handle}/categories",
            "POST /api/v1/blogs/{handle}/categories",
            "PATCH /api/v1/blogs/{handle}/categories/{id}",
            "PUT /api/v1/blogs/{handle}/categories/order",
            "DELETE /api/v1/blogs/{handle}/categories/{id}",
            // 태그
            "GET /api/v1/tags/{name}/posts",
            "GET /api/v1/blogs/{handle}/tags",
            // 댓글
            "GET /api/v1/posts/{postId}/comments",
            "POST /api/v1/posts/{postId}/comments",
            "PATCH /api/v1/comments/{id}",
            "DELETE /api/v1/comments/{id}",
            // 이미지
            "POST /api/v1/media",
            "GET /media/{key}",
            "GET /media/{key}/{size}",
            // 약관·개인정보처리방침
            "GET /api/v1/legal/terms",
            "GET /api/v1/legal/privacy",
            // 관리자 API(001이 정한 것, 결정 #8에 따라 Phase 5에서 구현)
            "PATCH /api/v1/admin/users/{id}/blog-limit",
            // 002 발견·피드(specs/002-discovery-feeds/contracts/api.md) — 좋아요·구독·구독 피드
            "PUT /api/v1/me/likes/{postId}",
            "DELETE /api/v1/me/likes/{postId}",
            "PUT /api/v1/me/subscriptions/{handle}",
            "DELETE /api/v1/me/subscriptions/{handle}",
            "GET /api/v1/me/feed",
            // 002 알림
            "GET /api/v1/me/notifications",
            "POST /api/v1/me/notifications/{id}/read",
            "POST /api/v1/me/notifications/bulk",
            // 002 검색·관련 글
            "GET /api/v1/search/posts",
            "GET /api/v1/posts/{id}/related",
            // 002 블로그 피드(RSS·Atom)와 사이트맵·robots(접두어 없음, 표준 형식)
            "GET /{handle}/rss",
            "GET /{handle}/atom",
            "GET /{handle}/category/{categoryId}/rss",
            "GET /sitemap.xml",
            "GET /sitemap/pages.xml",
            "GET /sitemap/posts-{n}.xml",
            "GET /robots.txt",
            // 003 포털(003 contracts/api.md): 주제·포털 메인·끝까지 읽음
            "GET /api/v1/topics",
            "GET /api/v1/topics/{slug}/posts",
            "GET /api/v1/portal",
            "GET /api/v1/portal/latest",
            "POST /api/v1/posts/{id}/read-complete",
            // 003 릴리스 노트 독자 API 6개(001 contracts "릴리스 노트")
            "GET /api/v1/release-notes",
            "GET /api/v1/release-notes/search",
            "GET /api/v1/release-notes/{version}",
            "GET /api/v1/release-notes/{version}/revisions",
            "GET /api/v1/release-notes/{version}/revisions/{revisionNo}",
            "POST /api/v1/me/release-notes/seen",
            // 003 관리자: 주제 4, 포털 8, 운영 설정 3, 릴리스 노트 관리 9(001 contracts "릴리스 노트 관리")
            "GET /api/v1/admin/topics",
            "POST /api/v1/admin/topics",
            "PATCH /api/v1/admin/topics/{id}",
            "PUT /api/v1/admin/topics/order",
            "GET /api/v1/admin/portal/curations",
            "POST /api/v1/admin/portal/curations",
            "PATCH /api/v1/admin/portal/curations/{id}",
            "DELETE /api/v1/admin/portal/curations/{id}",
            "GET /api/v1/admin/portal/exclusions",
            "PUT /api/v1/admin/portal/exclusions/{postId}",
            "DELETE /api/v1/admin/portal/exclusions/{postId}",
            "GET /api/v1/admin/portal/posts/{id}",
            "GET /api/v1/admin/settings",
            "PUT /api/v1/admin/settings/{key}",
            "DELETE /api/v1/admin/settings/{key}",
            "GET /api/v1/admin/release-notes",
            "POST /api/v1/admin/release-notes",
            "GET /api/v1/admin/release-notes/{id}",
            "PUT /api/v1/admin/release-notes/{id}",
            "POST /api/v1/admin/release-notes/{id}/publish",
            "POST /api/v1/admin/release-notes/{id}/unpublish",
            "DELETE /api/v1/admin/release-notes/{id}",
            "POST /api/v1/admin/release-notes/preview",
            "GET /api/v1/admin/release-notes/{id}/revisions",
            "GET /api/v1/admin/release-notes/{id}/revisions/{revisionNo}",
            // 004 (004 contracts/api.md) — 방명록·공지·사이드바·보관함·방문자
            "GET /api/v1/blogs/{handle}/guestbook",
            "POST /api/v1/blogs/{handle}/guestbook",
            "PATCH /api/v1/guestbook-entries/{id}",
            "DELETE /api/v1/guestbook-entries/{id}",
            "POST /api/v1/guestbook-entries/{id}/unlock",
            "GET /api/v1/blogs/{handle}/notices",
            "GET /api/v1/blogs/{handle}/sidebar",
            "GET /api/v1/blogs/{handle}/manage/sidebar",
            "PUT /api/v1/blogs/{handle}/sidebar",
            "GET /api/v1/blogs/{handle}/archive",
            "POST /api/v1/blogs/{handle}/visits",
            "GET /api/v1/blogs/{handle}/manage/stats",
            // 004 글 공개 옵션(US3): 보호 글 열기·예약 취소·비회원 댓글 내용 보기
            "POST /api/v1/posts/{id}/unlock",
            "POST /api/v1/posts/{id}/unschedule",
            "POST /api/v1/comments/{id}/unlock",
            // 004 블로그 백업(US4)
            "POST /api/v1/blogs/{handle}/exports",
            "GET /api/v1/blogs/{handle}/exports",
            "GET /api/v1/blogs/{handle}/exports/{id}/file",
            "GET /api/v1/blogs/{handle}/blocks",
            "PUT /api/v1/blogs/{handle}/blocks/{userId}",
            "DELETE /api/v1/blogs/{handle}/blocks/{userId}",
            // 005 (005 contracts/api.md) — CAPTCHA·신고·관리자 신고·숨김·회원
            "GET /api/v1/captcha/config");

    /** 공통 틀 대신 표준 형식(바이너리, 002 피드·사이트맵 XML, robots 텍스트)을 쓰는 경로(api-guidelines 4절 예외). */
    static final Set<String> BINARY = Set.of("GET /media/{}", "GET /media/{}/{}", "GET /{}/rss", "GET /{}/atom",
            "GET /{}/category/{}/rss", "GET /sitemap.xml", "GET /sitemap/pages.xml", "GET /sitemap/posts-{}.xml",
            "GET /robots.txt", "GET /api/v1/blogs/{}/exports/{}/file");

    /** 002~004가 001 응답·요청에 더한 필드(각 contracts/api.md "001 응답 확장"): 스키마 이름 → 필드. */
    static final Map<String, List<String>> RESPONSE_EXTENSIONS = Map.ofEntries(
            Map.entry("MeResponse", List.of("unreadNotificationCount", "unseenReleaseNote")),
            Map.entry("BlogResponse", List.of("subscriberCount", "subscribedByMe", "feedItemCount", "feedContentMode",
                    "portalEnabled", "defaultTopicId", "guestbookEnabled", "guestWriteEnabled")),
            Map.entry("PostDetailResponse", List.of("likeCount", "likedByMe", "topicId", "notice", "locked",
                    "scheduledAt")),
            Map.entry("UpdateBlogRequest", List.of("feedItemCount", "feedContentMode", "portalEnabled",
                    "defaultTopicId", "guestbookEnabled", "guestWriteEnabled")),
            Map.entry("DraftWriteRequest", List.of("topicId")),
            Map.entry("DraftResponse", List.of("topicId")),
            Map.entry("PublishSettingsRequest", List.of("topicId", "notice", "password", "scheduledAt")),
            Map.entry("DashboardResponse", List.of("newGuestbook7d", "recentGuestbook", "visitors")),
            Map.entry("PostSummaryResponse", List.of("notice", "scheduledAt")),
            // 004 비밀·비회원 댓글(004 contracts "001~003 요청·응답 확장")
            Map.entry("CommentResponse", List.of("secret")),
            Map.entry("AuthorResponse", List.of("guest")),
            Map.entry("CreateCommentRequest", List.of("secret", "guestName", "guestPassword")),
            Map.entry("UpdateCommentRequest", List.of("secret", "guestPassword")));

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Autowired
    private MockMvc mvc;

    private JsonNode doc;

    @BeforeEach
    void loadApiDocs() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        doc = new ObjectMapper().readTree(json);
    }

    @Test
    void everyContractEndpointIsDocumented() {
        Set<String> documented = documentedOperations();
        List<String> missing = CONTRACT.stream().filter(op -> !documented.contains(normalize(op))).toList();
        assertThat(missing).as("/v3/api-docs에 없는 계약 엔드포인트").isEmpty();
    }

    @Test
    void documentedApiEndpointsAreInTheContract() {
        Set<String> contract = new TreeSet<>(CONTRACT.stream().map(OpenApiContractTest::normalize).toList());
        List<String> extra = documentedOperations().stream().filter(op -> !contract.contains(op)).toList();
        assertThat(extra).as("계약(contracts/api.md)에 없는 엔드포인트").isEmpty();
    }

    @Test
    void everyJsonResponseUsesTheCommonEnvelope() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : doc.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (!HTTP_METHODS.contains(operation.getKey())) {
                    continue;
                }
                String op = normalize(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                if (BINARY.contains(op)) {
                    continue;
                }
                JsonNode responses = operation.getValue().get("responses");
                boolean hasSuccess = false;
                for (Map.Entry<String, JsonNode> response : responses.properties()) {
                    hasSuccess |= response.getKey().startsWith("2");
                    JsonNode content = response.getValue().get("content");
                    if (content == null) {
                        violations.add(op + " " + response.getKey() + ": 본문 스키마 없음");
                        continue;
                    }
                    for (Map.Entry<String, JsonNode> media : content.properties()) {
                        String problem = envelopeProblem(media.getValue().get("schema"));
                        if (problem != null) {
                            violations.add(op + " " + response.getKey() + " " + media.getKey() + ": " + problem);
                        }
                    }
                }
                if (!hasSuccess) {
                    violations.add(op + ": 2xx 응답 없음");
                }
            }
        }
        assertThat(violations).as("공통 틀이 아닌 응답").isEmpty();
    }

    @Test
    void responseExtensionsOf002And003AreDocumented() {
        JsonNode schemas = doc.get("components").get("schemas");
        List<String> missing = new ArrayList<>();
        RESPONSE_EXTENSIONS.forEach((schema, fields) -> {
            JsonNode node = schemas.get(schema);
            Set<String> properties = node == null || node.get("properties") == null ? Set.of()
                    : names(node.get("properties"));
            fields.stream().filter(field -> !properties.contains(field)).forEach(field -> missing.add(schema + "." + field));
        });
        assertThat(missing).as("문서에 없는 002~004 응답 확장 필드").isEmpty();
    }

    @Test
    void apiDocsAreDisabledInProd() throws Exception {
        List<PropertySource<?>> prod = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yml"));
        assertThat(prod).anySatisfy(source -> {
            assertThat(source.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
            assertThat(source.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
        });
    }

    /** 공통 틀이면 null, 아니면 이유. */
    private String envelopeProblem(JsonNode schema) {
        JsonNode envelope = resolve(schema);
        if (envelope == null || envelope.get("properties") == null) {
            return "스키마를 찾을 수 없음: " + schema;
        }
        Set<String> properties = names(envelope.get("properties"));
        if (!properties.contains("header") || !properties.contains("result")
                || !Set.of("header", "result", "totalCount", "nextCursor").containsAll(properties)) {
            return "공통 틀 아님: " + properties;
        }
        JsonNode header = resolve(envelope.get("properties").get("header"));
        if (header == null || header.get("properties") == null
                || !names(header.get("properties")).containsAll(Set.of("isSuccessful", "resultCode", "resultMessage"))) {
            return "header 형식 아님: " + header;
        }
        return null;
    }

    private JsonNode resolve(JsonNode schema) {
        if (schema == null) {
            return null;
        }
        JsonNode ref = schema.get("$ref");
        if (ref == null) {
            return schema;
        }
        String name = ref.asString().substring("#/components/schemas/".length());
        return doc.get("components").get("schemas").get(name);
    }

    private Set<String> documentedOperations() {
        Set<String> operations = new TreeSet<>();
        for (Map.Entry<String, JsonNode> path : doc.get("paths").properties()) {
            for (String method : names(path.getValue())) {
                if (HTTP_METHODS.contains(method)) {
                    operations.add(normalize(method.toUpperCase(Locale.ROOT) + " " + path.getKey()));
                }
            }
        }
        return operations;
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** 경로 변수 이름을 지운다: {@code GET /posts/{postId}} → {@code GET /posts/{}}. */
    static String normalize(String operation) {
        return operation.replaceAll("\\{[^}]*}", "{}");
    }
}
