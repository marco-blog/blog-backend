package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 글 노출 매트릭스 001 행 전체(T238, data-model "글 노출 매트릭스", FR-018, SC-004)를 공개 API 전부에서 한 번에 확인한다.
 * <p>
 * 행: PUBLISHED·PUBLIC·ACTIVE(유일하게 보임) / PUBLISHED·PRIVATE / DRAFT / DELETED / 삭제된 블로그의 글(DELETED 행) /
 * 작성자 SUSPENDED / 작성자 WITHDRAWN. 숨어야 하는 글은 제목에 {@link #SECRET}을 넣어, "주인 외"(비로그인·다른 회원·관리자)가
 * 받는 모든 응답 본문에 이 표식이 한 번도 나오지 않는지(노출 0건) 함께 본다.
 * <p>
 * 확인하는 API: 글 상세, 블로그 글 목록(전체·카테고리·태그), 블로그 정보·카테고리 트리의 {@code postCount}, 블로그 태그,
 * 서비스 태그 글 목록, 댓글 목록, 관리 목록(주인 전용). 전체 컨텍스트를 H2(MySQL 모드)로 띄운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:exposurematrix;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ExposureMatrixIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "password123";
    private static final String SECRET = "SECRET";
    private static final String TAG = "matrix";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void onlyPublishedPublicPostsOfActiveAuthorsAreVisibleToAnyoneButTheOwner() throws Exception {
        // 주인: 블로그 "owner"(카테고리 하나에 행별 글), 지울 두 번째 블로그 "ownerold"
        Cookie owner = signup("owner@example.com", "owner");
        long category = id(json(post("/api/v1/blogs/owner/categories"), "{\"name\":\"Cat\"}", owner)
                .andExpect(status().isCreated()).andReturn());
        long visible = publish(owner, "owner", "Visible", category, "PUBLIC");
        long privatePost = publish(owner, "owner", SECRET + " private", category, "PRIVATE");
        long draft = draft(owner, "owner", SECRET + " draft", category);
        long trashed = publish(owner, "owner", SECRET + " trashed", category, "PUBLIC");
        json(delete("/api/v1/posts/" + trashed), "", owner).andExpect(status().isOk());

        json(post("/api/v1/blogs"), "{\"handle\":\"ownerold\",\"title\":\"Old\"}", owner)
                .andExpect(status().isCreated());
        long inDeletedBlog = publish(owner, "ownerold", SECRET + " deleted blog", null, "PUBLIC");
        json(delete("/api/v1/blogs/ownerold"), "{\"password\":\"" + PASSWORD + "\"}", owner)
                .andExpect(status().isOk());

        // 작성자 SUSPENDED(정지는 006 관리 기능이라 DB에서 바꾼다), 작성자 WITHDRAWN(탈퇴 API)
        Cookie suspended = signup("suspended@example.com", "suspended");
        long bySuspended = publish(suspended, "suspended", SECRET + " suspended", null, "PUBLIC");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = (SELECT user_id FROM blogs WHERE handle = ?)",
                "suspended");
        Cookie withdrawn = signup("withdrawn@example.com", "withdrawn");
        long byWithdrawn = publish(withdrawn, "withdrawn", SECRET + " withdrawn", null, "PUBLIC");
        json(delete("/api/v1/me"), "{\"password\":\"" + PASSWORD + "\"}", withdrawn).andExpect(status().isOk());

        // 주인 외: 비로그인, 다른 회원, 관리자(관리자도 "주인 외"다)
        Cookie member = signup("member@example.com", "member");
        signup("boss@example.com", "boss");
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = (SELECT user_id FROM blogs WHERE handle = ?)", "boss");
        Cookie admin = login("boss@example.com");

        Map<Long, String> hidden = new LinkedHashMap<>();
        hidden.put(privatePost, "PUBLISHED·PRIVATE");
        hidden.put(draft, "DRAFT");
        hidden.put(trashed, "DELETED");
        hidden.put(inDeletedBlog, "삭제된 블로그");
        hidden.put(bySuspended, "작성자 SUSPENDED");
        hidden.put(byWithdrawn, "작성자 WITHDRAWN");

        Map<String, Cookie> others = new LinkedHashMap<>();
        others.put("비로그인", null);
        others.put("다른 회원", member);
        others.put("관리자", admin);

        for (Map.Entry<String, Cookie> viewer : others.entrySet()) {
            Cookie cookie = viewer.getValue();
            List<String> bodies = new ArrayList<>();

            // 상세: 보이는 글만 200, 나머지는 없는 글과 같은 404
            bodies.add(read(get("/api/v1/posts/" + visible), cookie).andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.contentMarkdown").doesNotExist())
                    .andReturn().getResponse().getContentAsString());
            for (long hiddenId : hidden.keySet()) {
                bodies.add(read(get("/api/v1/posts/" + hiddenId), cookie)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"))
                        .andExpect(jsonPath("$.result").doesNotExist())
                        .andReturn().getResponse().getContentAsString());
                bodies.add(read(get("/api/v1/posts/" + hiddenId + "/comments"), cookie)
                        .andExpect(status().isNotFound())
                        .andReturn().getResponse().getContentAsString());
            }
            read(get("/api/v1/posts/999999"), cookie).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"));

            // 블로그 목록: 전체·카테고리·태그 모두 보이는 글 하나
            for (String query : List.of("", "?category=" + category, "?tag=" + TAG)) {
                bodies.add(read(get("/api/v1/blogs/owner/posts" + query), cookie)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.result[*].id", contains((int) visible)))
                        .andExpect(jsonPath("$.totalCount").value(1))
                        .andReturn().getResponse().getContentAsString());
            }
            // 카테고리 postCount(블로그 정보·카테고리 트리)와 블로그 태그 postCount
            bodies.add(read(get("/api/v1/blogs/owner"), cookie)
                    .andExpect(jsonPath("$.result.categories[0].postCount").value(1))
                    .andReturn().getResponse().getContentAsString());
            bodies.add(read(get("/api/v1/blogs/owner/categories"), cookie)
                    .andExpect(jsonPath("$.result[0].postCount").value(1))
                    .andReturn().getResponse().getContentAsString());
            bodies.add(read(get("/api/v1/blogs/owner/tags"), cookie)
                    .andExpect(jsonPath("$.result[*].name", contains(TAG)))
                    .andExpect(jsonPath("$.result[0].postCount").value(1))
                    .andReturn().getResponse().getContentAsString());
            // 서비스 태그: 다른 블로그(정지·탈퇴·삭제된 블로그)의 같은 태그 글도 빠진다
            bodies.add(read(get("/api/v1/tags/" + TAG + "/posts"), cookie)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result[*].id", contains((int) visible)))
                    .andExpect(jsonPath("$.totalCount").value(1))
                    .andReturn().getResponse().getContentAsString());
            // 정지·탈퇴 회원과 삭제된 블로그는 블로그 자체가 404(정지 회원 블로그 첫 화면만 BLOG_RESTRICTED, 005 FR-042)
            for (String handle : List.of("ownerold", "suspended", "withdrawn")) {
                bodies.add(read(get("/api/v1/blogs/" + handle), cookie)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.header.resultCode").value(
                                handle.equals("suspended") ? "BLOG_RESTRICTED" : "BLOG_NOT_FOUND"))
                        .andReturn().getResponse().getContentAsString());
                bodies.add(read(get("/api/v1/blogs/" + handle + "/posts"), cookie)
                        .andReturn().getResponse().getContentAsString());
                bodies.add(read(get("/api/v1/blogs/" + handle + "/tags"), cookie)
                        .andReturn().getResponse().getContentAsString());
            }
            // 관리 목록은 주인 전용: 비로그인 401, 그 밖은 403
            bodies.add(read(get("/api/v1/blogs/owner/manage/posts"), cookie)
                    .andExpect(status().is(cookie == null ? 401 : 403))
                    .andReturn().getResponse().getContentAsString());
            bodies.add(read(get("/api/v1/blogs/owner/manage/posts?status=DELETED"), cookie)
                    .andExpect(status().is(cookie == null ? 401 : 403))
                    .andReturn().getResponse().getContentAsString());

            assertThat(bodies)
                    .as("%s에게 숨은 글이 노출됨(SC-004)", viewer.getKey())
                    .noneMatch(body -> body.contains(SECRET));
        }

        // 주인은 DRAFT·PRIVATE를 상세에서 보고, DELETED는 휴지통(관리 목록)에서만 본다. 공개 목록은 주인이 봐도 같다.
        read(get("/api/v1/posts/" + privatePost), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contentMarkdown").isNotEmpty());
        read(get("/api/v1/posts/" + draft), owner).andExpect(status().isOk());
        read(get("/api/v1/posts/" + trashed), owner).andExpect(status().isNotFound());
        read(get("/api/v1/blogs/owner/posts"), owner)
                .andExpect(jsonPath("$.result[*].id", contains((int) visible)));
        read(get("/api/v1/blogs/owner/manage/posts?status=DELETED"), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.result[*].id", contains((int) trashed)));
        MvcResult manage = read(get("/api/v1/blogs/owner/manage/posts"), owner).andExpect(status().isOk()).andReturn();
        List<Integer> managed = JsonPath.read(manage.getResponse().getContentAsString(), "$.result[*].id");
        assertThat(managed).contains((int) visible, (int) privatePost, (int) draft);
        // 삭제된 블로그의 글은 주인에게도 404(DELETED 행)
        read(get("/api/v1/posts/" + inDeletedBlog), owner).andExpect(status().isNotFound());
    }

    private long publish(Cookie cookie, String handle, String title, Long categoryId, String visibility)
            throws Exception {
        long id = draft(cookie, handle, title, categoryId);
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"" + visibility + "\"}", cookie)
                .andExpect(status().isOk());
        return id;
    }

    private long draft(Cookie cookie, String handle, String title, Long categoryId) throws Exception {
        return id(json(post("/api/v1/blogs/" + handle + "/posts/drafts"),
                "{\"title\":\"" + title + "\",\"contentMarkdown\":\"본문 " + title + "\",\"categoryId\":" + categoryId
                        + ",\"tags\":[\"" + TAG + "\"]}", cookie)
                .andExpect(status().isCreated())
                .andReturn());
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

    private Cookie login(String email) throws Exception {
        MvcResult result = json(post("/api/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null)
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE);
    }

    private ResultActions read(MockHttpServletRequestBuilder builder, Cookie cookie) throws Exception {
        if (cookie != null) {
            builder.cookie(cookie);
        }
        return mvc.perform(builder);
    }

    private ResultActions json(MockHttpServletRequestBuilder builder, String body, Cookie cookie) throws Exception {
        builder.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
        return read(builder, cookie);
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.id")).longValue();
    }
}
