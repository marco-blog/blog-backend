package net.java21.blog.backend.manage.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.manage.dto.BulkAction;
import net.java21.blog.backend.manage.dto.BulkPostRequest;
import net.java21.blog.backend.manage.dto.BulkPostResponse;
import net.java21.blog.backend.manage.dto.DashboardResponse;
import net.java21.blog.backend.manage.dto.ManagePostFilter;
import net.java21.blog.backend.manage.service.ManageDashboardService;
import net.java21.blog.backend.manage.service.ManagePostService;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 블로그 관리 API(T151): 주인만(주인 아님 403, 삭제된 블로그 404), 비로그인 401, 조건·페이지 전달, 응답 형식. */
@WebMvcTest(ManageController.class)
@Import(WebMvcTestSupport.class)
class ManageControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final PostSummaryResponse TRASHED = new PostSummaryResponse(5L, "버린 글", "요약", null, null,
            List.of(), 3, 0, PostVisibility.PRIVATE, PostStatus.DELETED, NOW, NOW, false, NOW,
            Instant.parse("2026-11-05T04:24:19Z"));
    private static final PostSummaryResponse LIVE = new PostSummaryResponse(6L, "글", null, null, null, List.of(), 0,
            0, PostVisibility.PUBLIC, PostStatus.DRAFT, null, NOW, true, null, null);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private ManageDashboardService dashboardService;
    @MockitoBean
    private ManagePostService postService;

    @Test
    void dashboard() throws Exception {
        when(dashboardService.dashboard(1L, "marco")).thenReturn(new DashboardResponse(2, List.of(LIVE), 0, List.of()));

        mvc.perform(get("/api/v1/blogs/marco/manage/dashboard").cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result.draftCount").value(2))
                .andExpect(jsonPath("$.result.recentPosts[0].hasDraft").value(true))
                .andExpect(jsonPath("$.result.recentPosts[0].deletedAt").doesNotExist())
                .andExpect(jsonPath("$.result.newComments7d").value(0))
                .andExpect(jsonPath("$.result.recentComments", hasSize(0)));
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/blogs/marco/manage/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/blogs/marco/manage/posts"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(dashboardService, postService);
    }

    @Test
    void notOwnerIs403AndDeletedBlogIs404() throws Exception {
        when(dashboardService.dashboard(2L, "marco"))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "not owner"));
        when(postService.posts(eq(1L), eq("gone"), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "deleted"));

        mvc.perform(get("/api/v1/blogs/marco/manage/dashboard").cookie(authCookies.user(2L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.result").isEmpty());
        mvc.perform(get("/api/v1/blogs/gone/manage/posts").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BLOG_NOT_FOUND"));
    }

    @Test
    void postsPassFiltersAndPageAndReturnPurgeAt() throws Exception {
        when(postService.posts(eq(1L), eq("marco"), any(), any()))
                .thenReturn(new PageImpl<>(List.of(TRASHED), PageRequest.of(1, 10), 11));

        mvc.perform(get("/api/v1/blogs/marco/manage/posts").cookie(authCookies.user(1L))
                        .param("status", "DELETED").param("visibility", "PRIVATE").param("category", "7")
                        .param("q", " 스프링 ").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(11))
                .andExpect(jsonPath("$.result[0].id").value(5))
                .andExpect(jsonPath("$.result[0].status").value("DELETED"))
                .andExpect(jsonPath("$.result[0].deletedAt").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result[0].purgeAt").value("2026-11-05T04:24:19Z"))
                .andExpect(jsonPath("$.result[0].tags", hasSize(0)));

        ArgumentCaptor<ManagePostFilter> filter = ArgumentCaptor.forClass(ManagePostFilter.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(postService).posts(eq(1L), eq("marco"), filter.capture(), pageable.capture());
        assertThat(filter.getValue())
                .isEqualTo(new ManagePostFilter(PostStatus.DELETED, PostVisibility.PRIVATE, 7L, "스프링"));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void postsWithoutFiltersAndBadStatus() throws Exception {
        when(postService.posts(anyLong(), anyString(), any(), any())).thenReturn(new PageImpl<>(List.of(LIVE)));

        mvc.perform(get("/api/v1/blogs/marco/manage/posts").cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].purgeAt").doesNotExist());
        verify(postService).posts(eq(1L), eq("marco"), eq(ManagePostFilter.ALL), any());

        mvc.perform(get("/api/v1/blogs/marco/manage/posts").cookie(authCookies.user(1L)).param("status", "GONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"));
    }

    @Test
    void bulkChangesVisibility() throws Exception {
        when(postService.bulk(eq(1L), eq("marco"), any())).thenReturn(new BulkPostResponse(3));

        mvc.perform(post("/api/v1/blogs/marco/manage/posts/bulk").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[1,2,3],\"action\":\"CHANGE_VISIBILITY\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.updated").value(3));

        verify(postService).bulk(1L, "marco",
                new BulkPostRequest(List.of(1L, 2L, 3L), BulkAction.CHANGE_VISIBILITY, PostVisibility.PRIVATE, null));
    }

    @Test
    void bulkMovesCategory() throws Exception {
        when(postService.bulk(eq(1L), eq("marco"), any())).thenReturn(new BulkPostResponse(2));

        mvc.perform(post("/api/v1/blogs/marco/manage/posts/bulk").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[1,2],\"action\":\"MOVE_CATEGORY\",\"categoryId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.updated").value(2));

        verify(postService).bulk(1L, "marco",
                new BulkPostRequest(List.of(1L, 2L), BulkAction.MOVE_CATEGORY, null, 3L));
    }

    @Test
    void bulkMoveToOtherBlogsCategoryIs404() throws Exception {
        when(postService.bulk(eq(1L), eq("marco"), any()))
                .thenThrow(new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "nope"));

        mvc.perform(post("/api/v1/blogs/marco/manage/posts/bulk").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[1],\"action\":\"MOVE_CATEGORY\",\"categoryId\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void bulkValidation() throws Exception {
        String hundredOne = LongStream.rangeClosed(1, 101).mapToObj(Long::toString)
                .collect(Collectors.joining(","));
        mvc.perform(post("/api/v1/blogs/marco/manage/posts/bulk").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[" + hundredOne + "],\"action\":\"DELETE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("postIds"));
        mvc.perform(post("/api/v1/blogs/marco/manage/posts/bulk").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[],\"action\":\"DELETE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("postIds"));
        mvc.perform(post("/api/v1/blogs/marco/manage/posts/bulk").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("action"));
        mvc.perform(post("/api/v1/blogs/marco/manage/posts/bulk").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[1],\"action\":\"RENAME\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"));
        verifyNoInteractions(postService);
    }

    @Test
    void bulkOnOthersPostsIs403() throws Exception {
        when(postService.bulk(eq(1L), eq("marco"), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "not this blog"));

        mvc.perform(post("/api/v1/blogs/marco/manage/posts/bulk").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[1,99],\"action\":\"DELETE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("FORBIDDEN"));
    }
}
