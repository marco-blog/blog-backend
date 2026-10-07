package net.java21.blog.backend.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
 * 글 주제 지정 흐름(003 T072, US3 AS2·4·5): 주제 없이 발행한 글은 최신 글에 있고 주제 페이지에는 없다. 주제를 바꿔 다시 발행하면 새 주제
 * 페이지에만 나온다. 블로그의 포털 노출을 끄면 포털 어디에도 없지만 블로그 글 목록·RSS에는 남는다. 캐시는 끈다({@code 0s}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:portaltopicflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "blog.portal.cache-ttl=0s",
        "blog.portal.new-member-delay=0s"
})
class PortalTopicFlowIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String TEXT = "가".repeat(250);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void topicChoiceAndBlogPortalSettingDecideWherePostsAppear() throws Exception {
        Cookie owner = signup("topicflow@example.com", "topicflow");
        long travel = topicId("domestic-travel");
        long it = topicId("it-internet");

        // 블로그 기본 주제는 GET에 보이고, 글 사본에는 front가 넣는다(backend가 자동으로 채우지 않음)
        json(patch("/api/v1/blogs/topicflow"), "{\"defaultTopicId\":" + travel + "}", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.defaultTopicId").value(travel))
                .andExpect(jsonPath("$.result.portalEnabled").value(true));

        long id = draft(owner, "topicflow", "{\"title\":\"첫 글\",\"contentMarkdown\":\"" + TEXT + "\",\"topicId\":"
                + travel + "}");
        // "선택 안 함": 사본의 topicId를 null로 바꾸고 발행
        json(put("/api/v1/posts/" + id + "/draft"),
                "{\"title\":\"첫 글\",\"contentMarkdown\":\"" + TEXT + "\",\"topicId\":null}", owner)
                .andExpect(status().isOk());
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PUBLIC\"}", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.topicId").value(org.hamcrest.Matchers.nullValue()));

        assertThat(latestIds()).contains(id);
        assertThat(topicPostIds("domestic-travel")).doesNotContain(id);
        assertThat(topicPostIds("travel-food")).doesNotContain(id);

        // 주제를 정해 다시 발행 → 그 주제 페이지·대분류 페이지에 나온다
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PUBLIC\",\"topicId\":" + travel + "}", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.topicId").value(travel));
        assertThat(topicPostIds("domestic-travel")).containsExactly(id);
        assertThat(topicPostIds("travel-food")).containsExactly(id);

        // 다른 주제로 바꾸면 이전 주제 페이지에서 빠진다
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PUBLIC\",\"topicId\":" + it + "}", owner)
                .andExpect(status().isOk());
        assertThat(topicPostIds("domestic-travel")).isEmpty();
        assertThat(topicPostIds("it-internet")).containsExactly(id);
        assertThat(body(mvc.perform(get("/api/v1/posts/" + id + "/draft").cookie(owner))))
                .contains("\"topicId\":" + it);

        // 대분류·없는 주제는 발행되지 않는다
        json(post("/api/v1/posts/" + id + "/publish"),
                "{\"visibility\":\"PUBLIC\",\"topicId\":" + topicId("knowledge") + "}", owner)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("TOPIC_NOT_SELECTABLE"));
        json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"PUBLIC\",\"topicId\":987654}", owner)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("TOPIC_NOT_FOUND"));

        // 블로그 포털 끔 → 포털에는 없고 블로그 목록·RSS에는 있다
        json(patch("/api/v1/blogs/topicflow"), "{\"portalEnabled\":false}", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.portalEnabled").value(false));
        assertThat(latestIds()).doesNotContain(id);
        assertThat(topicPostIds("it-internet")).isEmpty();
        String home = body(mvc.perform(get("/api/v1/portal")));
        assertThat(home).doesNotContain("\"id\":" + id + ",");
        assertThat(body(mvc.perform(get("/api/v1/blogs/topicflow/posts")))).contains("\"id\":" + id);
        assertThat(body(mvc.perform(get("/topicflow/rss")))).contains("/topicflow/" + id);
    }

    private List<Long> latestIds() throws Exception {
        return ids(body(mvc.perform(get("/api/v1/portal/latest")).andExpect(status().isOk())), "$.result[*].id");
    }

    private List<Long> topicPostIds(String slug) throws Exception {
        return ids(body(mvc.perform(get("/api/v1/topics/" + slug + "/posts")).andExpect(status().isOk())),
                "$.result[*].id");
    }

    private long topicId(String slug) {
        return jdbc.queryForObject("SELECT id FROM topics WHERE slug = ?", Long.class, slug);
    }

    private static List<Long> ids(String json, String path) {
        return JsonPath.<List<Number>>read(json, path).stream().map(Number::longValue).toList();
    }

    private long draft(Cookie cookie, String handle, String body) throws Exception {
        MvcResult result = json(post("/api/v1/blogs/" + handle + "/posts/drafts"), body, cookie)
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.id")).longValue();
    }

    private Cookie signup(String email, String handle) throws Exception {
        MvcResult result = json(post("/api/v1/auth/signup"), """
                {"email":"%s","password":"password123","nickname":"%s","handle":"%s",
                 "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06"}"""
                .formatted(email, handle, handle), null)
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

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
