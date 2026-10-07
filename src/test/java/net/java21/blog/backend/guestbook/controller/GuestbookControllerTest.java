package net.java21.blog.backend.guestbook.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.guestbook.dto.GuestbookEntryResponse;
import net.java21.blog.backend.guestbook.dto.GuestbookUpdateRequest;
import net.java21.blog.backend.guestbook.dto.GuestbookWriteRequest;
import net.java21.blog.backend.guestbook.service.GuestbookService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 방명록 API(T035, 004 contracts/api.md 방명록 절). */
@WebMvcTest(GuestbookController.class)
@Import(WebMvcTestSupport.class)
class GuestbookControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final String VISITOR = "3f2b7c1e-8a4d-4f6b-9c2e-1d5a7b9e0c3f";
    private static final GuestbookEntryResponse REPLY = new GuestbookEntryResponse(8L, null, true, false,
            AuthorResponse.member(1L, "마르코", null), NOW, NOW, List.of());
    private static final GuestbookEntryResponse SECRET = new GuestbookEntryResponse(7L, null, true, false,
            AuthorResponse.guest("손님"), NOW, NOW, List.of(REPLY));
    private static final GuestbookEntryResponse CREATED = new GuestbookEntryResponse(9L, "놀러 왔어요", false, false,
            AuthorResponse.guest("손님"), NOW, NOW, List.of());

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private GuestbookService guestbookService;

    @Test
    void listIsPublicPage() throws Exception {
        when(guestbookService.list(eq("marco"), isNull(), eq(PageRequest.of(1, 20, org.springframework.data.domain
                .Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))))
                .thenReturn(new PageImpl<>(List.of(SECRET), PageRequest.of(1, 20), 21));

        mvc.perform(get("/api/v1/blogs/marco/guestbook?page=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(21))
                .andExpect(jsonPath("$.result", hasSize(1)))
                .andExpect(jsonPath("$.result[0].secret").value(true))
                .andExpect(jsonPath("$.result[0].content").doesNotExist())
                .andExpect(jsonPath("$.result[0].author.guest").value(true))
                .andExpect(jsonPath("$.result[0].author.nickname").value("손님"))
                .andExpect(jsonPath("$.result[0].replies[0].author.guest").value(false))
                .andExpect(jsonPath("$.result[0].replies[0].replies", hasSize(0)));
    }

    @Test
    void disabledGuestbookIs404() throws Exception {
        when(guestbookService.list(eq("marco"), eq(2L), any()))
                .thenThrow(new BusinessException(ErrorCode.GUESTBOOK_DISABLED, "off"));
        mvc.perform(get("/api/v1/blogs/marco/guestbook").cookie(authCookies.user(2L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("GUESTBOOK_DISABLED"));
    }

    @Test
    void memberCreateReturns201WithLocation() throws Exception {
        when(guestbookService.create(eq("marco"), eq(2L), any(), any())).thenReturn(CREATED);

        mvc.perform(post("/api/v1/blogs/marco/guestbook").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"놀러 왔어요\",\"secret\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/guestbook-entries/9"))
                .andExpect(jsonPath("$.result.id").value(9));
        verify(guestbookService).create(eq("marco"), eq(2L),
                eq(new GuestbookWriteRequest("놀러 왔어요", true, null, null, null)), any(ClientInfo.class));
    }

    @Test
    void anonymousCreateGoesToServiceAsGuest() throws Exception {
        when(guestbookService.create(eq("marco"), isNull(), any(), any())).thenReturn(CREATED);

        mvc.perform(post("/api/v1/blogs/marco/guestbook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"놀러 왔어요\",\"guestName\":\"손님\",\"guestPassword\":\"1234\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.author.guest").value(true));
        verify(guestbookService).create(eq("marco"), isNull(),
                eq(new GuestbookWriteRequest("놀러 왔어요", null, null, "손님", "1234")),
                eq(new ClientInfo("127.0.0.1", null)));
    }

    @Test
    void guestRejectionsAndRateLimit() throws Exception {
        when(guestbookService.create(eq("closed"), isNull(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED, "guests off"));
        mvc.perform(post("/api/v1/blogs/closed/guestbook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕\",\"guestName\":\"손님\",\"guestPassword\":\"1234\"}"))
                .andExpect(status().isUnauthorized());

        when(guestbookService.create(eq("busy"), isNull(), any(), any()))
                .thenThrow(BusinessException.retryAfter(ErrorCode.TOO_MANY_REQUESTS, "slow down", 42));
        mvc.perform(post("/api/v1/blogs/busy/guestbook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕\",\"guestName\":\"손님\",\"guestPassword\":\"1234\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.header.resultCode").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void contentValidation() throws Exception {
        mvc.perform(post("/api/v1/blogs/marco/guestbook").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("content"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("REQUIRED"));
        mvc.perform(post("/api/v1/blogs/marco/guestbook").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "가".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("TOO_LONG"));
        mvc.perform(patch("/api/v1/guestbook-entries/7").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "가".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(guestbookService);
    }

    @Test
    void guestUpdatePassesPasswordVisitorKeyAndIp() throws Exception {
        when(guestbookService.update(eq(7L), isNull(), any(), any(), any())).thenReturn(CREATED);

        mvc.perform(patch("/api/v1/guestbook-entries/7").cookie(new Cookie("visitor_id", VISITOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"고침\",\"secret\":true,\"guestPassword\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(9));
        verify(guestbookService).update(eq(7L), isNull(), eq(new GuestbookUpdateRequest("고침", true, "1234")),
                any(), eq("127.0.0.1"));
    }

    @Test
    void wrongGuestPasswordIs403AndTooManyAttemptsIs429() throws Exception {
        when(guestbookService.update(eq(7L), isNull(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.GUEST_PASSWORD_MISMATCH, "mismatch"));
        mvc.perform(patch("/api/v1/guestbook-entries/7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestPassword\":\"틀림\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("GUEST_PASSWORD_MISMATCH"));

        when(guestbookService.unlock(eq(7L), eq("1234"), any(), any()))
                .thenThrow(BusinessException.retryAfter(ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED, "locked", 600));
        mvc.perform(post("/api/v1/guestbook-entries/7/unlock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestPassword\":\"1234\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "600"));
    }

    @Test
    void deleteTakesGuestPasswordInBodyOrNoBodyForMembers() throws Exception {
        mvc.perform(delete("/api/v1/guestbook-entries/7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestPassword\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(guestbookService).delete(eq(7L), isNull(), eq("1234"), isNull(), eq("127.0.0.1"));

        mvc.perform(delete("/api/v1/guestbook-entries/8").cookie(authCookies.user(1L)))
                .andExpect(status().isOk());
        verify(guestbookService).delete(eq(8L), eq(1L), isNull(), eq("u:1"), eq("127.0.0.1"));
    }

    @Test
    void unlockReturnsContent() throws Exception {
        when(guestbookService.unlock(eq(9L), eq("1234"), isNull(), eq("127.0.0.1"))).thenReturn(CREATED);
        mvc.perform(post("/api/v1/guestbook-entries/9/unlock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestPassword\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content").value("놀러 왔어요"));
    }
}
