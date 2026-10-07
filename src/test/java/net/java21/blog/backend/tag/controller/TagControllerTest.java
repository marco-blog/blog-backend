package net.java21.blog.backend.tag.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.CategoryRef;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.support.WebMvcTestSupport;
import net.java21.blog.backend.tag.dto.BlogTagResponse;
import net.java21.blog.backend.tag.dto.TaggedPostSummaryResponse;
import net.java21.blog.backend.tag.service.TagService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 태그 API(T170): 서비스 전체 태그별 글(PostSummary + blogHandle, 페이지)과 블로그 태그 목록, 비로그인 가능. */
@WebMvcTest(TagController.class)
@Import(WebMvcTestSupport.class)
class TagControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private TagService tagService;

    @Test
    void taggedPostsArePagedSummariesWithBlogHandle() throws Exception {
        PostSummaryResponse summary = new PostSummaryResponse(7L, "글", "요약", null, new CategoryRef(3L, "Spring"),
                List.of("spring boot"), 1, 0, PostVisibility.PUBLIC, PostStatus.PUBLISHED, NOW, NOW, false, false,
                null, null);
        when(tagService.taggedPosts(eq("spring boot"), any()))
                .thenReturn(new PageImpl<>(List.of(new TaggedPostSummaryResponse(summary, "marco")),
                        PageRequest.of(1, 20), 21));

        mvc.perform(get("/api/v1/tags/{name}/posts", "spring boot").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", hasSize(1)))
                .andExpect(jsonPath("$.result[0].id").value(7))
                .andExpect(jsonPath("$.result[0].blogHandle").value("marco"))
                .andExpect(jsonPath("$.result[0].category.name").value("Spring"))
                .andExpect(jsonPath("$.result[0].tags[0]").value("spring boot"))
                .andExpect(jsonPath("$.result[0].post").doesNotExist())
                .andExpect(jsonPath("$.result[0].deletedAt").doesNotExist())
                .andExpect(jsonPath("$.totalCount").value(21));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(tagService).taggedPosts(eq("spring boot"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
    }

    @Test
    void blogTags() throws Exception {
        when(tagService.blogTags("marco")).thenReturn(List.of(new BlogTagResponse("spring", 2)));

        mvc.perform(get("/api/v1/blogs/marco/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].name").value("spring"))
                .andExpect(jsonPath("$.result[0].postCount").value(2));

        when(tagService.blogTags("gone")).thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));
        mvc.perform(get("/api/v1/blogs/gone/tags"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BLOG_NOT_FOUND"));
    }

    @Test
    void badPageIs400() throws Exception {
        mvc.perform(get("/api/v1/tags/spring/posts").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"));
    }
}
