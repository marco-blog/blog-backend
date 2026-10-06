package net.java21.blog.backend.like.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.like.dto.LikeStateResponse;
import net.java21.blog.backend.like.service.PostLikeService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 좋아요 API(T017): PUT·DELETE 200 공통 틀과 {@code result} 형식, 비로그인 401, 404, {@code Cache-Control: no-store}. */
@WebMvcTest(PostLikeController.class)
@Import(WebMvcTestSupport.class)
class PostLikeControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @MockitoBean
    private PostLikeService service;

    @Test
    void putLikes() throws Exception {
        when(service.like(7L, 123L)).thenReturn(new LikeStateResponse(123L, true, 5));

        mvc.perform(put("/api/v1/me/likes/123").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.resultCode").value("OK"))
                .andExpect(jsonPath("$.result.postId").value(123))
                .andExpect(jsonPath("$.result.liked").value(true))
                .andExpect(jsonPath("$.result.likeCount").value(5))
                .andExpect(jsonPath("$.totalCount").doesNotExist())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void deleteUnlikesWithStateNotNullResult() throws Exception {
        when(service.unlike(7L, 123L)).thenReturn(new LikeStateResponse(123L, false, 4));

        mvc.perform(delete("/api/v1/me/likes/123").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.liked").value(false))
                .andExpect(jsonPath("$.result.likeCount").value(4))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(put("/api/v1/me/likes/123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(delete("/api/v1/me/likes/123")).andExpect(status().isUnauthorized());
        verify(service, never()).like(anyLong(), anyLong());
    }

    @Test
    void invisiblePostIs404() throws Exception {
        when(service.like(7L, 9L)).thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "x"));
        when(service.unlike(7L, 9L)).thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "x"));

        mvc.perform(put("/api/v1/me/likes/9").cookie(authCookies.user(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mvc.perform(delete("/api/v1/me/likes/9").cookie(authCookies.user(7L)))
                .andExpect(status().isNotFound());
    }
}
