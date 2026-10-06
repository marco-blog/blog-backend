package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
 * 002 노출 확인(T098, SC-004): 글 노출 매트릭스 001 행 전체(PRIVATE·DRAFT·DELETED·작성자 SUSPENDED·WITHDRAWN·삭제된 블로그)를
 * 구독 피드·RSS·Atom·카테고리 RSS·사이트맵·관련 글·좋아요(404)에서 한 번에 확인한다. 숨어야 하는 글은 제목에 {@link #SECRET}을 넣어
 * 주인 외가 받는 모든 응답에 한 번도 나오지 않는지(노출 0건) 본다. 검색은 FULLTEXT가 필요해 {@code PostSearchRepositoryTest}(MySQL)가 확인한다.
 * 전체 컨텍스트를 H2(MySQL 모드)로 띄운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:discoveryexposure;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.base-url=https://blog.example.com"
})
class DiscoveryExposureIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "password123";
    private static final String SECRET = "SECRET";
    private static final String TAG = "matrix";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void hiddenPostsNeverAppearInFeedsSitemapsRelatedPostsOrLikes() throws Exception {
        Cookie owner = signup("owner@example.com", "owner");
        Cookie reader = signup("reader@example.com", "reader");
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
        Cookie suspended = signup("suspended@example.com", "suspended");
        long bySuspended = publish(suspended, "suspended", SECRET + " suspended", null, "PUBLIC");
        Cookie withdrawn = signup("withdrawn@example.com", "withdrawn");
        long byWithdrawn = publish(withdrawn, "withdrawn", SECRET + " withdrawn", null, "PUBLIC");

        // 독자는 숨기 전에 모든 블로그를 구독한다(구독 피드에서 빠지는지 본다)
        for (String handle : List.of("owner", "ownerold", "suspended", "withdrawn")) {
            json(put("/api/v1/me/subscriptions/" + handle), "", reader).andExpect(status().isOk());
        }
        json(delete("/api/v1/blogs/ownerold"), "{\"password\":\"" + PASSWORD + "\"}", owner).andExpect(status().isOk());
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = (SELECT user_id FROM blogs WHERE handle = ?)",
                "suspended");
        json(delete("/api/v1/me"), "{\"password\":\"" + PASSWORD + "\"}", withdrawn).andExpect(status().isOk());

        Map<Long, String> hidden = new LinkedHashMap<>();
        hidden.put(privatePost, "PUBLISHED·PRIVATE");
        hidden.put(draft, "DRAFT");
        hidden.put(trashed, "DELETED");
        hidden.put(inDeletedBlog, "삭제된 블로그");
        hidden.put(bySuspended, "작성자 SUSPENDED");
        hidden.put(byWithdrawn, "작성자 WITHDRAWN");

        Map<String, Cookie> others = new LinkedHashMap<>();
        others.put("비로그인", null);
        others.put("구독한 독자", reader);
        for (Map.Entry<String, Cookie> viewer : others.entrySet()) {
            Cookie cookie = viewer.getValue();
            List<String> bodies = new ArrayList<>();

            // RSS·Atom·카테고리 RSS: 보이는 글 하나만
            for (String path : List.of("/owner/rss", "/owner/atom", "/owner/category/" + category + "/rss")) {
                String xml = body(read(get(path), cookie).andExpect(status().isOk()));
                assertThat(xml).as(path).contains("Visible", "https://blog.example.com/owner/" + visible);
                bodies.add(xml);
            }
            // 정지·탈퇴 회원과 삭제된 블로그의 피드는 404
            for (String handle : List.of("ownerold", "suspended", "withdrawn")) {
                bodies.add(body(read(get("/" + handle + "/rss"), cookie).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.header.resultCode").value("BLOG_NOT_FOUND"))));
                bodies.add(body(read(get("/" + handle + "/atom"), cookie).andExpect(status().isNotFound())));
            }
            // 사이트맵: 보이는 글과 그 블로그 홈만
            bodies.add(body(read(get("/sitemap.xml"), cookie).andExpect(status().isOk())));
            String pages = body(read(get("/sitemap/pages.xml"), cookie).andExpect(status().isOk()));
            assertThat(pages).contains("<loc>https://blog.example.com/owner</loc>")
                    .doesNotContain("/ownerold<", "/suspended<", "/withdrawn<");
            String posts = body(read(get("/sitemap/posts-1.xml"), cookie).andExpect(status().isOk()));
            assertThat(posts).contains("<loc>https://blog.example.com/owner/" + visible + "</loc>");
            for (long hiddenId : hidden.keySet()) {
                assertThat(posts).as(hidden.get(hiddenId)).doesNotContain("/" + hiddenId + "</loc>");
            }
            read(get("/sitemap/posts-2.xml"), cookie).andExpect(status().isNotFound());
            bodies.add(pages);
            bodies.add(posts);
            // 관련 글: 같은 카테고리·태그의 숨은 글은 후보가 아니다. 숨은 글을 기준으로 하면 404
            bodies.add(body(read(get("/api/v1/posts/" + visible + "/related"), cookie).andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").isEmpty())));
            for (long hiddenId : hidden.keySet()) {
                bodies.add(body(read(get("/api/v1/posts/" + hiddenId + "/related"), cookie)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"))));
            }

            assertThat(bodies).as("%s에게 숨은 글이 노출됨(SC-004)", viewer.getKey())
                    .noneMatch(text -> text.contains(SECRET));
        }

        // 구독 피드: 구독한 네 블로그 중 보이는 글 하나만
        String feed = body(read(get("/api/v1/me/feed"), reader).andExpect(status().isOk())
                .andExpect(jsonPath("$.result[*].id", contains((int) visible)))
                .andExpect(jsonPath("$.totalCount").value(1)));
        assertThat(feed).doesNotContain(SECRET);
        // 좋아요: 보이는 글만, 숨은 글은 없는 글과 같은 404
        json(put("/api/v1/me/likes/" + visible), "", reader).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.likeCount").value(1));
        for (long hiddenId : hidden.keySet()) {
            String response = body(json(put("/api/v1/me/likes/" + hiddenId), "", reader)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND")));
            assertThat(response).doesNotContain(SECRET);
        }
        // 주인은 비공개 글의 관련 글을 볼 수 있다(후보는 여전히 본문 노출 가능 글만)
        read(get("/api/v1/posts/" + privatePost + "/related"), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.result[*].id", contains((int) visible)));
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

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.id")).longValue();
    }
}
