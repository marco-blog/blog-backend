package net.java21.blog.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import net.java21.blog.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * US2 핵심 흐름(T174의 backend 쪽, quickstart #9·10): 상위·하위 카테고리 생성 → 글 3편을 서로 다른 카테고리·태그로 발행 →
 * 카테고리·태그별 목록에 공개 글만 최신순 → 11번째 태그 거부 → 상위 카테고리 삭제 시 글이 미분류로.
 * 전체 컨텍스트(보안·서비스·JPA·새 트랜잭션의 태그 만들기)를 H2(MySQL 모드)로 띄운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:categorytagflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CategoryTagFlowTest {

    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mvc;

    @Test
    void categoriesAndTagsOrganizePublicPosts() throws Exception {
        MvcResult signup = mvc.perform(json(post("/api/v1/auth/signup"), """
                        {"email":"us2@example.com","password":"password123","nickname":"분류","handle":"sorter",
                         "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06"}"""))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie access = signup.getResponse().getCookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE);

        long spring = id(mvc.perform(json(post("/api/v1/blogs/sorter/categories"), "{\"name\":\"Spring\"}")
                .cookie(access)).andExpect(status().isCreated()).andReturn());
        long boot = id(mvc.perform(json(post("/api/v1/blogs/sorter/categories"),
                "{\"name\":\"Boot\",\"parentId\":" + spring + "}").cookie(access))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(json(post("/api/v1/blogs/sorter/categories"), "{\"name\":\"깊음\",\"parentId\":" + boot + "}")
                        .cookie(access))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("CATEGORY_DEPTH_EXCEEDED"));
        mvc.perform(json(post("/api/v1/blogs/sorter/categories"), "{\"name\":\"Spring\"}").cookie(access))
                .andExpect(status().isConflict());

        long a = publish(access, "A 스프링", spring, "[\" Spring \",\"JPA\"]", "PUBLIC");
        long b = publish(access, "B 부트", boot, "[\"spring\",\"Spring Boot\"]", "PUBLIC");
        long c = publish(access, "C 비공개 부트", boot, "[\"spring\"]", "PRIVATE");

        // 상위 카테고리 목록은 하위 글을 포함, 공개 글만 최신순
        mvc.perform(get("/api/v1/blogs/sorter/posts").param("category", String.valueOf(spring)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[*].id", contains((int) b, (int) a)))
                .andExpect(jsonPath("$.result[0].category.name").value("Boot"))
                .andExpect(jsonPath("$.result[0].tags", contains("spring", "spring boot")));
        mvc.perform(get("/api/v1/blogs/sorter/posts").param("tag", "SPRING"))
                .andExpect(jsonPath("$.result[*].id", contains((int) b, (int) a)));
        mvc.perform(get("/api/v1/tags/spring/posts"))
                .andExpect(jsonPath("$.result[*].id", contains((int) b, (int) a)))
                .andExpect(jsonPath("$.result[0].blogHandle").value("sorter"))
                .andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(get("/api/v1/blogs/sorter"))
                .andExpect(jsonPath("$.result.categories[0].postCount").value(2))
                .andExpect(jsonPath("$.result.categories[0].children[0].postCount").value(1));
        mvc.perform(get("/api/v1/blogs/sorter/tags"))
                .andExpect(jsonPath("$.result[0].name").value("spring"))
                .andExpect(jsonPath("$.result[0].postCount").value(2));
        mvc.perform(get("/api/v1/posts/" + a))
                .andExpect(jsonPath("$.result.category.id").value(spring))
                .andExpect(jsonPath("$.result.tags", contains("jpa", "spring")));
        mvc.perform(get("/api/v1/blogs/sorter/posts").param("category", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CATEGORY_NOT_FOUND"));

        // 11번째 태그 거부
        String elevenTags = "[\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"t8\",\"t9\",\"t10\",\"t11\"]";
        long d = draft(access, "D", null, elevenTags);
        mvc.perform(json(post("/api/v1/posts/" + d + "/publish"), "{\"visibility\":\"PUBLIC\"}").cookie(access))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("TAG_LIMIT_EXCEEDED"));

        // 상위 카테고리 삭제 → 하위도 삭제되고 글은 미분류
        mvc.perform(delete("/api/v1/blogs/sorter/categories/" + spring).header("Origin", ORIGIN).cookie(access))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/blogs/sorter/categories")).andExpect(jsonPath("$.result", hasSize(0)));
        for (long id : new long[] {a, b}) {
            mvc.perform(get("/api/v1/posts/" + id)).andExpect(jsonPath("$.result.category").value(nullValue()));
        }
        mvc.perform(get("/api/v1/posts/" + c).cookie(access))
                .andExpect(jsonPath("$.result.category").value(nullValue()));
        assertThat(a).isLessThan(b);
    }

    private long publish(Cookie access, String title, long categoryId, String tags, String visibility)
            throws Exception {
        long id = draft(access, title, categoryId, tags);
        mvc.perform(json(post("/api/v1/posts/" + id + "/publish"), "{\"visibility\":\"" + visibility + "\"}")
                        .cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.category.id").value(categoryId));
        return id;
    }

    private long draft(Cookie access, String title, Long categoryId, String tags) throws Exception {
        return id(mvc.perform(json(post("/api/v1/blogs/sorter/posts/drafts"),
                        "{\"title\":\"" + title + "\",\"contentMarkdown\":\"본문 " + title + "\",\"categoryId\":"
                                + categoryId + ",\"tags\":" + tags + "}")
                        .cookie(access))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.result.id")).longValue();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
