package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * 006 T015(US1 AS5, FR-097, research A10): 블로그 관리 API({@code /api/v1/blogs/{handle}/manage/**})와 블로그 주인 전용 API(001 블로그
 * 설정·삭제·카테고리·임시저장, 004 사이드바·백업·차단)는 비로그인 401 {@code UNAUTHENTICATED}, 다른 회원 403 {@code FORBIDDEN}, 삭제된
 * 블로그의 주인 404, 주인 2xx다. {@code /manage/**} 매핑은 모두 이 표에 있어야 한다("새 블로그 관리 API는 이 표에 행을 더한다").
 */
class ManageEndpointAccessMatrixTest extends AdminConsoleIntegrationSupport {

    /** 행 하나. {@code vars}는 경로 변수 이름 → 값, {@code body}는 요청 본문(GET은 null), {@code strict}면 주인 응답이 2xx여야 한다. */
    private record Row(String method, String pattern, Function<Fixture, Map<String, String>> vars,
            Function<Fixture, String> body, boolean strict) {

        Row(String method, String pattern, Function<Fixture, String> body) {
            this(method, pattern, f -> Map.of(), body, true);
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    /** 시험 준비물(주인의 블로그·카테고리·다른 블로그, 차단할 회원, 백업 번호). */
    private record Fixture(String handle, String spareHandle, long categoryId, long postId, long blockeeId,
            String sidebar, long exportId) {
    }

    private static final String NONE = null;

    /** 블로그 주인만 쓰는 API 전체. 순서대로 주인이 부른다(지우는 행은 뒤에). */
    private static final List<Row> TABLE = List.of(
            // 001 블로그 관리
            new Row("GET", "/api/v1/blogs/{handle}/manage/dashboard", f -> NONE),
            new Row("GET", "/api/v1/blogs/{handle}/manage/posts", f -> NONE),
            new Row("POST", "/api/v1/blogs/{handle}/manage/posts/bulk",
                    f -> "{\"postIds\":[" + f.postId() + "],\"action\":\"CHANGE_VISIBILITY\",\"visibility\":\"PRIVATE\"}"),
            new Row("GET", "/api/v1/blogs/{handle}/manage/comments", f -> NONE),
            // 005 받은 트랙백(US3)
            new Row("GET", "/api/v1/blogs/{handle}/manage/trackbacks", f -> NONE),
            // 004 통계·사이드바
            new Row("GET", "/api/v1/blogs/{handle}/manage/stats", f -> NONE),
            new Row("GET", "/api/v1/blogs/{handle}/manage/sidebar", f -> NONE),
            new Row("PUT", "/api/v1/blogs/{handle}/sidebar", Fixture::sidebar),
            // 001 블로그 설정(002 피드·003 포털·004 방명록 설정 포함)·카테고리·임시저장
            new Row("PATCH", "/api/v1/blogs/{handle}", f -> "{\"title\":\"바뀐 제목\"}"),
            new Row("POST", "/api/v1/blogs/{handle}/categories", f -> "{\"name\":\"새 카테고리\"}"),
            new Row("PATCH", "/api/v1/blogs/{handle}/categories/{id}", f -> Map.of("id", String.valueOf(f.categoryId())),
                    f -> "{\"name\":\"고친 이름\"}", true),
            new Row("PUT", "/api/v1/blogs/{handle}/categories/order",
                    f -> "[{\"id\":" + f.categoryId() + ",\"parentId\":null,\"sortOrder\":0}]"),
            new Row("POST", "/api/v1/blogs/{handle}/posts/drafts", f -> "{\"title\":\"임시\",\"contentMarkdown\":\"본문\"}"),
            new Row("GET", "/api/v1/blogs/{handle}/posts/drafts/latest", f -> NONE),
            // 004 백업·차단
            new Row("GET", "/api/v1/blogs/{handle}/exports", f -> NONE),
            new Row("GET", "/api/v1/blogs/{handle}/exports/{id}/file", f -> Map.of("id", String.valueOf(f.exportId())),
                    f -> NONE, false),
            new Row("GET", "/api/v1/blogs/{handle}/blocks", f -> NONE),
            new Row("PUT", "/api/v1/blogs/{handle}/blocks/{userId}",
                    f -> Map.of("userId", String.valueOf(f.blockeeId())), f -> NONE, true),
            new Row("DELETE", "/api/v1/blogs/{handle}/blocks/{userId}",
                    f -> Map.of("userId", String.valueOf(f.blockeeId())), f -> NONE, true),
            new Row("POST", "/api/v1/blogs/{handle}/exports", f -> NONE),
            new Row("DELETE", "/api/v1/blogs/{handle}/categories/{id}",
                    f -> Map.of("id", String.valueOf(f.categoryId())), f -> NONE, true),
            new Row("DELETE", "/api/v1/blogs/{handle}", f -> Map.of("handle", f.spareHandle()),
                    f -> "{\"password\":\"" + PASSWORD + "\"}", true));

    @Test
    void everyManageMappingIsInTheTable() {
        List<String> listed = TABLE.stream().map(Row::toString).toList();
        List<String> missing = endpoints("/api/v1/blogs/").stream()
                .filter(endpoint -> endpoint.pattern().contains("/manage"))
                .map(Endpoint::toString)
                .filter(endpoint -> !listed.contains(endpoint))
                .toList();
        assertThat(missing).as("새 블로그 관리 API는 이 표에 행을 더한다").isEmpty();
        List<String> all = endpoints("/api/v1/blogs/").stream().map(Endpoint::toString).toList();
        assertThat(listed).as("표의 행은 실제 매핑").allSatisfy(row -> assertThat(all).contains(row));
    }

    @Test
    void onlyTheOwnerPasses() throws Exception {
        Member owner = signup("mgown");
        Member other = signup("mgoth");
        Member blockee = signup("mgblk");
        Fixture fixture = prepare(owner, blockee);
        Fixture deleted = deletedBlog(owner, fixture);

        List<String> failures = new ArrayList<>();
        for (Row row : TABLE) {
            String path = path(row, fixture);
            String body = row.body().apply(fixture);
            HttpMethod method = HttpMethod.valueOf(row.method());

            Reply anonymous = send(method, path, body, null);
            if (anonymous.status() != 401 || !"UNAUTHENTICATED".equals(anonymous.resultCode())) {
                failures.add("비로그인 " + row + " -> " + anonymous.status() + " " + anonymous.body());
            }
            Reply stranger = send(method, path, body, other.cookie());
            if (stranger.status() != 403 || !"FORBIDDEN".equals(stranger.resultCode())) {
                failures.add("다른 회원 " + row + " -> " + stranger.status() + " " + stranger.body());
            }
            if (!row.toString().equals("DELETE /api/v1/blogs/{handle}")) {
                Reply gone = send(method, path(row, deleted), row.body().apply(deleted), owner.cookie());
                if (gone.status() != 404) {
                    failures.add("삭제된 블로그 주인 " + row + " -> " + gone.status() + " " + gone.body());
                }
            }
            Reply mine = send(method, path, body, owner.cookie());
            boolean passed = row.strict() ? mine.status() / 100 == 2
                    : mine.status() != 401 && mine.status() != 403;
            if (!passed) {
                failures.add("주인 " + row + " -> " + mine.status() + " " + mine.body());
            }
        }
        assertThat(failures).isEmpty();
    }

    private Fixture prepare(Member owner, Member blockee) throws Exception {
        String handle = owner.handle();
        Reply category = send(HttpMethod.POST, "/api/v1/blogs/" + handle + "/categories", "{\"name\":\"준비\"}",
                owner.cookie());
        Reply draft = send(HttpMethod.POST, "/api/v1/blogs/" + handle + "/posts/drafts",
                "{\"title\":\"글\",\"contentMarkdown\":\"본문\"}", owner.cookie());
        long postId = ((Number) draft.read("$.result.id")).longValue();
        assertThat(send(HttpMethod.POST, "/api/v1/posts/" + postId + "/publish", "{\"visibility\":\"PUBLIC\"}",
                owner.cookie()).status()).isEqualTo(200);
        Reply sidebar = send(HttpMethod.GET, "/api/v1/blogs/" + handle + "/manage/sidebar", null, owner.cookie());
        String spare = uniqueHandle("mgspr");
        assertThat(send(HttpMethod.POST, "/api/v1/blogs", "{\"handle\":\"" + spare + "\"}", owner.cookie()).status())
                .isEqualTo(201);
        return new Fixture(handle, spare, ((Number) category.read("$.result.id")).longValue(), postId, blockee.id(),
                JsonPath.parse((Object) JsonPath.read(sidebar.body(), "$.result")).jsonString(),
                Long.parseLong(MISSING_ID));
    }

    /** 주인의 다른 블로그를 하나 더 만들어 지운다. 그 블로그 주소로 부르면 404여야 한다. */
    private Fixture deletedBlog(Member owner, Fixture fixture) throws Exception {
        String gone = uniqueHandle("mggone");
        assertThat(send(HttpMethod.POST, "/api/v1/blogs", "{\"handle\":\"" + gone + "\"}", owner.cookie()).status())
                .isEqualTo(201);
        assertThat(send(HttpMethod.DELETE, "/api/v1/blogs/" + gone, "{\"password\":\"" + PASSWORD + "\"}",
                owner.cookie()).status()).isEqualTo(200);
        return new Fixture(gone, gone, fixture.categoryId(), fixture.postId(), fixture.blockeeId(), fixture.sidebar(),
                fixture.exportId());
    }

    private static String path(Row row, Fixture fixture) {
        Map<String, String> vars = row.vars().apply(fixture);
        String path = row.pattern().replace("{handle}", vars.getOrDefault("handle", fixture.handle()));
        for (Map.Entry<String, String> var : vars.entrySet()) {
            path = path.replace("{" + var.getKey() + "}", var.getValue());
        }
        return path;
    }
}
