package net.java21.blog.backend.admin.spam;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.admin.spam.dto.BannedWordRequest;
import net.java21.blog.backend.admin.spam.dto.BannedWordResponse;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.spam.BannedWordService;
import net.java21.blog.backend.spam.domain.BannedWordAction;
import net.java21.blog.backend.spam.domain.BannedWordScope;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 005 T064: 금칙어 API(목록·추가 201+Location·변경·삭제), 오류 형식, 관리자 외 404. */
@WebMvcTest(AdminBannedWordController.class)
@Import(WebMvcTestSupport.class)
class AdminBannedWordControllerTest {

    private static final long ADMIN = 5L;
    private static final BannedWordResponse WORD = new BannedWordResponse(3L, "bad", BannedWordScope.ALL,
            BannedWordAction.MASK, new BannedWordResponse.Creator(ADMIN, "관리자"), Instant.parse("2026-10-07T00:00:00Z"),
            Instant.parse("2026-10-07T00:00:00Z"));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private BannedWordService service;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void listCreateUpdateDelete() throws Exception {
        when(service.list(eq("ba"), any())).thenReturn(new PageImpl<>(List.of(WORD), PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/v1/admin/banned-words?q=ba").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.result[0].word").value("bad"))
                .andExpect(jsonPath("$.result[0].createdBy.nickname").value("관리자"));

        when(service.create(eq(ADMIN), eq(new BannedWordRequest("bad", "ALL", "MASK")), anyString())).thenReturn(WORD);
        mvc.perform(post("/api/v1/admin/banned-words").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"bad\",\"scope\":\"ALL\",\"action\":\"MASK\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/banned-words/3"))
                .andExpect(jsonPath("$.result.scope").value("ALL"));

        when(service.update(eq(ADMIN), eq(3L), eq(new BannedWordRequest(null, null, "REJECT")), anyString()))
                .thenReturn(WORD);
        mvc.perform(patch("/api/v1/admin/banned-words/3").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REJECT\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/admin/banned-words/3").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service).delete(eq(ADMIN), eq(3L), anyString());
    }

    @Test
    void errorsUseCommonFormat() throws Exception {
        when(service.create(eq(ADMIN), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.BANNED_WORD_EXISTS, "exists"));
        mvc.perform(post("/api/v1/admin/banned-words").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"bad\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("BANNED_WORD_EXISTS"));
        doThrow(new BusinessException(ErrorCode.BANNED_WORD_NOT_FOUND, "x")).when(service)
                .delete(eq(ADMIN), eq(9L), anyString());
        mvc.perform(delete("/api/v1/admin/banned-words/9").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BANNED_WORD_NOT_FOUND"));
    }

    @Test
    void nonAdminsGet404() throws Exception {
        mvc.perform(get("/api/v1/admin/banned-words").cookie(authCookies.user(6L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/admin/banned-words"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
