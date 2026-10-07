package net.java21.blog.backend.comment.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.dto.AuthorResponse;
import net.java21.blog.backend.comment.dto.CommentResponse;
import net.java21.blog.backend.comment.dto.CreateCommentRequest;
import net.java21.blog.backend.comment.dto.UpdateCommentRequest;
import net.java21.blog.backend.comment.service.CommentService;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 댓글 API(T187): {@code GET/POST /posts/{postId}/comments}, {@code PATCH/DELETE /comments/{id}}, 비로그인 POST 401(AS5), 404·403·422. */
@WebMvcTest(CommentController.class)
@Import(WebMvcTestSupport.class)
class CommentControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final CommentResponse REPLY = new CommentResponse(2L, "답글", AuthorResponse.member(1L, "주인", null),
            false, NOW, NOW, List.of());
    private static final CommentResponse PLACEHOLDER = new CommentResponse(1L, null, null, true, NOW, NOW,
            List.of(REPLY));
    private static final CommentResponse CREATED = new CommentResponse(3L, "<b>안녕</b>",
            AuthorResponse.member(2L, "작성자", null), false, NOW, NOW, List.of());

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private CommentService commentService;

    @Test
    void listIsPublicAndNestsReplies() throws Exception {
        when(commentService.list(100L, null)).thenReturn(List.of(PLACEHOLDER));

        mvc.perform(get("/api/v1/posts/100/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result", hasSize(1)))
                .andExpect(jsonPath("$.result[0].deleted").value(true))
                .andExpect(jsonPath("$.result[0].content").doesNotExist())
                .andExpect(jsonPath("$.result[0].replies[0].id").value(2))
                .andExpect(jsonPath("$.result[0].replies[0].author.nickname").value("주인"))
                .andExpect(jsonPath("$.result[0].replies[0].author.userId").value(1))
                .andExpect(jsonPath("$.result[0].replies[0].createdAt").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result[0].replies[0].replies", hasSize(0)));
    }

    @Test
    void listPassesViewerAndInvisiblePostIs404() throws Exception {
        when(commentService.list(101L, 1L)).thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "nope"));

        mvc.perform(get("/api/v1/posts/101/comments").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(commentService.create(eq(2L), eq(100L), any())).thenReturn(CREATED);

        mvc.perform(post("/api/v1/posts/100/comments").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"<b>안녕</b>\",\"parentId\":null}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/comments/3"))
                .andExpect(jsonPath("$.result.content").value("<b>안녕</b>"))
                .andExpect(jsonPath("$.result.author.nickname").value("작성자"));
        verify(commentService).create(2L, 100L, new CreateCommentRequest("<b>안녕</b>", null));
    }

    @Test
    void anonymousCannotWrite() throws Exception {
        mvc.perform(post("/api/v1/posts/100/comments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        mvc.perform(patch("/api/v1/comments/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/comments/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(commentService);
    }

    @Test
    void contentIsRequiredAndAtMost1000Characters() throws Exception {
        mvc.perform(post("/api/v1/posts/100/comments").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("content"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("REQUIRED"));
        mvc.perform(post("/api/v1/posts/100/comments").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "가".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.header.fieldErrors[0].params.max").value(1000));
        verifyNoInteractions(commentService);
    }

    @Test
    void ruleViolationsMapToStatus() throws Exception {
        when(commentService.create(eq(2L), eq(100L), any()))
                .thenThrow(new BusinessException(ErrorCode.REPLY_DEPTH_EXCEEDED, "depth"));
        when(commentService.create(eq(2L), eq(101L), any()))
                .thenThrow(new BusinessException(ErrorCode.COMMENTS_DISABLED, "off"));

        mvc.perform(post("/api/v1/posts/100/comments").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"답\",\"parentId\":5}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("REPLY_DEPTH_EXCEEDED"));
        mvc.perform(post("/api/v1/posts/101/comments").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"답\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("COMMENTS_DISABLED"));
    }

    @Test
    void updateByAuthor() throws Exception {
        when(commentService.update(2L, 3L, new UpdateCommentRequest("고침"))).thenReturn(CREATED);

        mvc.perform(patch("/api/v1/comments/3").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"고침\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(3));
    }

    @Test
    void updateByOtherIs403AndMissingIs404() throws Exception {
        when(commentService.update(eq(1L), eq(3L), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "not author"));
        when(commentService.update(eq(1L), eq(9L), any()))
                .thenThrow(new BusinessException(ErrorCode.COMMENT_NOT_FOUND, "missing"));

        mvc.perform(patch("/api/v1/comments/3").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"고침\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("FORBIDDEN"));
        mvc.perform(patch("/api/v1/comments/9").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"고침\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("COMMENT_NOT_FOUND"));
    }

    @Test
    void deleteReturnsEmptySuccessAndPropagatesForbidden() throws Exception {
        mvc.perform(delete("/api/v1/comments/3").cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(commentService).delete(1L, 3L);

        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "no")).when(commentService).delete(5L, 3L);
        mvc.perform(delete("/api/v1/comments/3").cookie(authCookies.user(5L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void stateChangeWithoutAllowedOriginIsRejected() throws Exception {
        mvc.perform(delete("/api/v1/comments/3").cookie(authCookies.user(1L)).header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"));
        verifyNoInteractions(commentService);
    }
}
