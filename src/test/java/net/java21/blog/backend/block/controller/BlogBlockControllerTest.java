package net.java21.blog.backend.block.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.block.dto.BlockedUserResponse;
import net.java21.blog.backend.block.service.BlogBlockService;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 차단 API(T115): 목록 페이지·{@code totalCount}, {@code PUT} 멱등 200, {@code DELETE} 200, 422·404·403, 비로그인 401, no-store. */
@WebMvcTest(BlogBlockController.class)
@Import(WebMvcTestSupport.class)
class BlogBlockControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final BlockedUserResponse TROLL =
            new BlockedUserResponse(new BlockedUserResponse.BlockedUser(2L, "troll", "/media/pk"), NOW);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private BlogBlockService blockService;

    @Test
    void listIsPagedNewestFirst() throws Exception {
        Pageable expected = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(blockService.list(eq(1L), eq("marco"), eq(expected)))
                .thenReturn(new PageImpl<>(List.of(TROLL), expected, 6));

        mvc.perform(get("/api/v1/blogs/marco/blocks").param("page", "1").param("size", "5")
                        .cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.totalCount").value(6))
                .andExpect(jsonPath("$.result[0].user.userId").value(2))
                .andExpect(jsonPath("$.result[0].user.nickname").value("troll"))
                .andExpect(jsonPath("$.result[0].user.profileImageUrl").value("/media/pk"))
                .andExpect(jsonPath("$.result[0].blockedAt").value("2026-10-07T03:00:00Z"));
    }

    @Test
    void blockAndUnblock() throws Exception {
        when(blockService.block(1L, "marco", 2L)).thenReturn(TROLL);
        mvc.perform(put("/api/v1/blogs/marco/blocks/2").cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.user.userId").value(2));

        mvc.perform(delete("/api/v1/blogs/marco/blocks/2").cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true));
        verify(blockService).unblock(1L, "marco", 2L);
    }

    @Test
    void errorsAndLogin() throws Exception {
        mvc.perform(get("/api/v1/blogs/marco/blocks")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/blogs/marco/blocks/2")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/blogs/marco/blocks/2")).andExpect(status().isUnauthorized());
        verifyNoInteractions(blockService);

        when(blockService.block(1L, "marco", 1L)).thenThrow(new BusinessException(ErrorCode.CANNOT_BLOCK_SELF, "x"));
        mvc.perform(put("/api/v1/blogs/marco/blocks/1").cookie(authCookies.user(1L)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("CANNOT_BLOCK_SELF"));
        when(blockService.block(1L, "marco", 9L)).thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND, "x"));
        mvc.perform(put("/api/v1/blogs/marco/blocks/9").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("USER_NOT_FOUND"));
        doThrow(new BusinessException(ErrorCode.BLOCK_NOT_FOUND, "x")).when(blockService).unblock(1L, "marco", 9L);
        mvc.perform(delete("/api/v1/blogs/marco/blocks/9").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BLOCK_NOT_FOUND"));
        when(blockService.block(2L, "marco", 3L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        mvc.perform(put("/api/v1/blogs/marco/blocks/3").cookie(authCookies.user(2L)))
                .andExpect(status().isForbidden());
    }
}
