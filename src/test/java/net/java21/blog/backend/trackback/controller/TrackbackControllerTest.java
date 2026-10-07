package net.java21.blog.backend.trackback.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import net.java21.blog.backend.trackback.domain.PingErrorCode;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.dto.ManagedTrackbackResponse;
import net.java21.blog.backend.trackback.dto.TrackbackPingResponse;
import net.java21.blog.backend.trackback.dto.TrackbackResponse;
import net.java21.blog.backend.trackback.service.TrackbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 트랙백 API(005 T092, contracts/api.md "트랙백"). */
@WebMvcTest(TrackbackController.class)
@Import(WebMvcTestSupport.class)
class TrackbackControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private TrackbackService service;

    @Test
    void anonymousListIsAPage() throws Exception {
        when(service.list(eq(42L), isNull(), any(), any())).thenReturn(new PageImpl<>(List.of(
                new TrackbackResponse(1L, "제목", null, "블로그", "https://ext.example/p", NOW, false)),
                PageRequest.of(1, 10), 11));

        mvc.perform(get("/api/v1/posts/42/trackbacks?page=1&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.totalCount").value(11))
                .andExpect(jsonPath("$.result[0].url").value("https://ext.example/p"))
                .andExpect(jsonPath("$.result[0].excerpt").value(nullValue()))
                .andExpect(jsonPath("$.result[0].internal").value(false))
                .andExpect(jsonPath("$.result[0].receivedAt").value("2026-10-06T00:00:00Z"));
    }

    @Test
    void listOfInvisiblePostIs404() throws Exception {
        when(service.list(eq(42L), isNull(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "x"));

        mvc.perform(get("/api/v1/posts/42/trackbacks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"));
    }

    @Test
    void deleteIs200NullAnd401403404() throws Exception {
        mvc.perform(delete("/api/v1/trackbacks/5").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(service).delete(7L, 5L);

        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "x")).when(service).delete(8L, 5L);
        mvc.perform(delete("/api/v1/trackbacks/5").cookie(authCookies.user(8L)))
                .andExpect(status().isForbidden());
        doThrow(new BusinessException(ErrorCode.TRACKBACK_NOT_FOUND, "x")).when(service).delete(7L, 6L);
        mvc.perform(delete("/api/v1/trackbacks/6").cookie(authCookies.user(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("TRACKBACK_NOT_FOUND"));
        mvc.perform(delete("/api/v1/trackbacks/5")).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerManagedListIsNoStore() throws Exception {
        when(service.managed(eq(7L), eq("marco"), any())).thenReturn(new PageImpl<>(List.of(
                new ManagedTrackbackResponse(1L, "제목", "요약", null, "https://ext.example/p", NOW, true, true,
                        new ManagedTrackbackResponse.PostRef(42L, "받은 글"))), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/v1/blogs/marco/manage/trackbacks").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result[0].hidden").value(true))
                .andExpect(jsonPath("$.result[0].internal").value(true))
                .andExpect(jsonPath("$.result[0].post.title").value("받은 글"));
        mvc.perform(get("/api/v1/blogs/marco/manage/trackbacks")).andExpect(status().isUnauthorized());
    }

    @Test
    void pingsAreOwnerOnly() throws Exception {
        when(service.pings(7L, 42L)).thenReturn(List.of(new TrackbackPingResponse(3L, "https://ext.example/tb",
                PingStatus.FAILED, PingErrorCode.TIMEOUT, "Timed out", NOW, NOW)));
        when(service.pings(8L, 42L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));

        mvc.perform(get("/api/v1/posts/42/trackback-pings").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result[0].status").value("FAILED"))
                .andExpect(jsonPath("$.result[0].errorCode").value("TIMEOUT"))
                .andExpect(jsonPath("$.result[0].attemptedAt").value("2026-10-06T00:00:00Z"));
        mvc.perform(get("/api/v1/posts/42/trackback-pings").cookie(authCookies.user(8L)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/posts/42/trackback-pings")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCannotDelete() throws Exception {
        mvc.perform(delete("/api/v1/trackbacks/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
