package net.java21.blog.backend.search.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.CategoryRef;
import net.java21.blog.backend.search.SearchProperties;
import net.java21.blog.backend.search.dto.SearchPostResponse;
import net.java21.blog.backend.search.service.PostSearchService;
import net.java21.blog.backend.search.service.SearchQueryParser;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 검색 API(T062): {@code GET /api/v1/search/posts?q=} 비로그인 200 Page&lt;SearchPost&gt;, {@code q} 없음·1자·101자 400
 * {@code VALIDATION_FAILED}(fieldErrors[0].field = q). 검증은 실제 {@link SearchQueryParser}로 한다.
 */
@WebMvcTest(SearchController.class)
@Import(WebMvcTestSupport.class)
class SearchControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PostSearchService service;

    @Test
    void anonymousSearchReturnsPage() throws Exception {
        SearchPostResponse post = new SearchPostResponse(5L, "스프링 입문", "요약", null, new CategoryRef(3L, "Spring"),
                List.of("java"), 10, 2, PostVisibility.PUBLIC, PostStatus.PUBLISHED, NOW, NOW, false,
                new SearchPostResponse.BlogRef("marco", "마르코의 블로그"));
        when(service.search(eq("스프링"), isNull(), any())).thenReturn(new PageImpl<>(List.of(post), Pageable.ofSize(20), 41));

        mvc.perform(get("/api/v1/search/posts").param("q", "스프링").param("page", "1").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.totalCount").value(41))
                .andExpect(jsonPath("$.result[0].id").value(5))
                .andExpect(jsonPath("$.result[0].title").value("스프링 입문"))
                .andExpect(jsonPath("$.result[0].category.name").value("Spring"))
                .andExpect(jsonPath("$.result[0].tags[0]").value("java"))
                .andExpect(jsonPath("$.result[0].hasDraft").value(false))
                .andExpect(jsonPath("$.result[0].blog.handle").value("marco"))
                .andExpect(jsonPath("$.result[0].blog.title").value("마르코의 블로그"))
                .andExpect(jsonPath("$.result[0].thumbnailUrl").value(nullValue()));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(eq("스프링"), isNull(), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void blogParameterSearchesOneBlog() throws Exception {
        when(service.search(eq("스프링"), eq("marco"), any())).thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(20), 0));
        mvc.perform(get("/api/v1/search/posts").param("q", "스프링").param("blog", "marco"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
        when(service.search(eq("스프링"), eq("ghost"), any()))
                .thenThrow(new net.java21.blog.backend.common.error.BusinessException(
                        net.java21.blog.backend.common.error.ErrorCode.BLOG_NOT_FOUND, "Blog not found"));
        mvc.perform(get("/api/v1/search/posts").param("q", "스프링").param("blog", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BLOG_NOT_FOUND"));
    }

    @Test
    void invalidQueriesAre400OnFieldQ() throws Exception {
        SearchQueryParser parser = new SearchQueryParser(new SearchProperties(5, 2));
        when(service.search(any(), any(), any())).thenAnswer(invocation -> {
            parser.parse(invocation.getArgument(0));
            throw new AssertionError("should not reach");
        });

        mvc.perform(get("/api/v1/search/posts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("q"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(get("/api/v1/search/posts").param("q", "스"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("q"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("TOO_SHORT"))
                .andExpect(jsonPath("$.header.fieldErrors[0].params.min").value(2));
        mvc.perform(get("/api/v1/search/posts").param("q", "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("q"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.header.fieldErrors[0].params.max").value(100));
    }
}
