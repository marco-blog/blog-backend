package net.java21.blog.backend.comment.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import jakarta.servlet.http.Cookie;

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
            false, false, NOW, NOW, List.of());
    private static final CommentResponse PLACEHOLDER = new CommentResponse(1L, null, null, true, false, NOW, NOW,
            List.of(REPLY));
    private static final CommentResponse CREATED = new CommentResponse(3L, "<b>안녕</b>",
            AuthorResponse.member(2L, "작성자", null), false, false, NOW, NOW, List.of());

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private CommentService commentService;

    @Test
    void listIsPublicAndNestsReplies() throws Exception {
        when(commentService.list(eq(100L), isNull(), any())).thenReturn(List.of(PLACEHOLDER));

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
        when(commentService.list(eq(101L), eq(1L), any())).thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "nope"));

        mvc.perform(get("/api/v1/posts/101/comments").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(commentService.create(eq(2L), eq(100L), any(), any(), any())).thenReturn(CREATED);

        mvc.perform(post("/api/v1/posts/100/comments").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"<b>안녕</b>\",\"parentId\":null}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/comments/3"))
                .andExpect(jsonPath("$.result.content").value("<b>안녕</b>"))
                .andExpect(jsonPath("$.result.author.nickname").value("작성자"));
        verify(commentService).create(eq(2L), eq(100L), eq(new CreateCommentRequest("<b>안녕</b>", null)), any(), any());
    }

    /** 004: 비로그인 쓰기는 비회원 글로 서비스가 판단한다(허용하지 않는 블로그면 401). */
    @Test
    void anonymousWriteIsAGuestWriteDecidedByTheService() throws Exception {
        when(commentService.create(isNull(), eq(100L), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED, "guest write disabled"));
        mvc.perform(post("/api/v1/posts/100/comments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));

        CommentResponse guest = new CommentResponse(4L, "안녕", AuthorResponse.guest("손님"), false, true, NOW, NOW,
                List.of());
        when(commentService.create(isNull(), eq(101L), any(), any(), any())).thenReturn(guest);
        mvc.perform(post("/api/v1/posts/101/comments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕\",\"secret\":true,\"guestName\":\"손님\","
                                + "\"guestPassword\":\"1234\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.author.guest").value(true))
                .andExpect(jsonPath("$.result.author.userId").doesNotExist())
                .andExpect(jsonPath("$.result.author.nickname").value("손님"))
                .andExpect(jsonPath("$.result.secret").value(true))
                .andExpect(jsonPath("$.result.guestPassword").doesNotExist());
        verify(commentService).create(isNull(), eq(101L),
                eq(new CreateCommentRequest("안녕", null, true, "손님", "1234")), any(), any());
    }

    /** 005 T070: CAPTCHA 토큰 바인딩, 속도 429 + Retry-After, 반복 422, 금칙어 필드 오류 형식. */
    @Test
    void spamDefenseErrorsUseCommonFormat() throws Exception {
        when(commentService.create(isNull(), eq(100L), any(), any(), any()))
                .thenThrow(BusinessException.retryAfter(ErrorCode.TOO_MANY_REQUESTS, "slow", 30))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_CONTENT_SPAM, "dup"))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "banned",
                        List.of(net.java21.blog.backend.common.api.FieldError.of("content", "BANNED_WORD"))))
                .thenThrow(new BusinessException(ErrorCode.CAPTCHA_FAILED, "captcha"));
        String body = "{\"content\":\"안녕\",\"guestName\":\"손님\",\"guestPassword\":\"1234\","
                + "\"captchaToken\":\"e2e-pass\"}";
        mvc.perform(post("/api/v1/posts/100/comments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Retry-After", "30"));
        mvc.perform(post("/api/v1/posts/100/comments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("DUPLICATE_CONTENT_SPAM"));
        mvc.perform(post("/api/v1/posts/100/comments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("content"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("BANNED_WORD"));
        mvc.perform(post("/api/v1/posts/100/comments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("CAPTCHA_FAILED"));
        verify(commentService, org.mockito.Mockito.atLeastOnce()).create(isNull(), eq(100L),
                eq(new CreateCommentRequest("안녕", null, null, "손님", "1234", "e2e-pass")), any(), any());
    }

    @Test
    void guestUpdateDeleteAndUnlockSendThePasswordInTheBody() throws Exception {
        CommentResponse guest = new CommentResponse(4L, "비밀", AuthorResponse.guest("손님"), false, true, NOW, NOW,
                List.of());
        when(commentService.update(isNull(), eq(4L), eq(new UpdateCommentRequest("고침", true, "1234")),
                eq("v:3f1c2b8e-1111-2222-3333-444455556666"), any(), any())).thenReturn(guest);
        mvc.perform(patch("/api/v1/comments/4")
                        .cookie(new Cookie("visitor_id", "3f1c2b8e-1111-2222-3333-444455556666"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"고침\",\"secret\":true,\"guestPassword\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(4));

        mvc.perform(delete("/api/v1/comments/4").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestPassword\":\"1234\"}"))
                .andExpect(status().isOk());
        verify(commentService).delete(isNull(), eq(4L), eq("1234"), isNull(), any(), any());

        doThrow(new BusinessException(ErrorCode.GUEST_PASSWORD_MISMATCH, "x")).when(commentService)
                .delete(isNull(), eq(5L), eq("nope"), isNull(), any(), any());
        mvc.perform(delete("/api/v1/comments/5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestPassword\":\"nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("GUEST_PASSWORD_MISMATCH"));

        when(commentService.unlock(isNull(), eq(4L), eq("1234"), isNull(), any(), any())).thenReturn(guest);
        mvc.perform(post("/api/v1/comments/4/unlock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestPassword\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content").value("비밀"));

        when(commentService.unlock(isNull(), eq(6L), isNull(), isNull(), any(), any()))
                .thenThrow(BusinessException.retryAfter(ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED, "x", 600));
        mvc.perform(post("/api/v1/comments/6/unlock"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "600"));
    }

    @Test
    void lockedPostCommentsAre403() throws Exception {
        when(commentService.list(eq(102L), isNull(), any()))
                .thenThrow(new BusinessException(ErrorCode.POST_LOCKED, "locked"));
        mvc.perform(get("/api/v1/posts/102/comments"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("POST_LOCKED"));
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
        when(commentService.create(eq(2L), eq(100L), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.REPLY_DEPTH_EXCEEDED, "depth"));
        when(commentService.create(eq(2L), eq(101L), any(), any(), any()))
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
        when(commentService.update(eq(2L), eq(3L), eq(new UpdateCommentRequest("고침")), eq("u:2"), any(), any())).thenReturn(CREATED);

        mvc.perform(patch("/api/v1/comments/3").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"고침\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(3));
    }

    @Test
    void updateByOtherIs403AndMissingIs404() throws Exception {
        when(commentService.update(eq(1L), eq(3L), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "not author"));
        when(commentService.update(eq(1L), eq(9L), any(), any(), any(), any()))
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
        verify(commentService).delete(eq(1L), eq(3L), isNull(), eq("u:1"), any(), any());

        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "no")).when(commentService)
                .delete(eq(5L), eq(3L), isNull(), any(), any(), any());
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
