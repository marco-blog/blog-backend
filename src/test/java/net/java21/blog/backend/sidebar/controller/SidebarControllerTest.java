package net.java21.blog.backend.sidebar.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.sidebar.domain.SidebarItemType;
import net.java21.blog.backend.sidebar.dto.SidebarConfigRequest;
import net.java21.blog.backend.sidebar.dto.SidebarItemRequest;
import net.java21.blog.backend.sidebar.dto.SidebarPostResponse;
import net.java21.blog.backend.sidebar.dto.SidebarViewResponse;
import net.java21.blog.backend.sidebar.service.SidebarService;
import net.java21.blog.backend.stats.dto.VisitorCountsResponse;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 사이드바 API(T053): 공개 GET, 주인 GET·PUT, 남의 블로그 403, 모르는 항목 400. */
@WebMvcTest(SidebarController.class)
@Import(WebMvcTestSupport.class)
class SidebarControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private SidebarService sidebarService;

    @Test
    void publicViewHasEnabledItemsAndNullForOff() throws Exception {
        when(sidebarService.view("marco")).thenReturn(new SidebarViewResponse(
                List.of(SidebarItemType.PROFILE, SidebarItemType.RECENT_POSTS, SidebarItemType.VISITORS),
                List.of(new SidebarPostResponse(123L, "첫 글", Instant.parse("2026-10-06T04:24:19Z"))), null, null,
                null, null, new VisitorCountsResponse(12, 30, 1520)));

        mvc.perform(get("/api/v1/blogs/marco/sidebar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[2]").value("VISITORS"))
                .andExpect(jsonPath("$.result.recentPosts[0].title").value("첫 글"))
                .andExpect(jsonPath("$.result.popularPosts").doesNotExist())
                .andExpect(jsonPath("$.result.visitors.total").value(1520));
    }

    @Test
    void ownerReadsAndSavesConfig() throws Exception {
        SidebarConfigRequest config = new SidebarConfigRequest(List.of(
                new SidebarItemRequest(SidebarItemType.TAGS, true)));
        when(sidebarService.config(1L, "marco")).thenReturn(config);
        mvc.perform(get("/api/v1/blogs/marco/manage/sidebar").cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[0].type").value("TAGS"))
                .andExpect(jsonPath("$.result.items[0].enabled").value(true));

        when(sidebarService.save(eq(1L), eq("marco"), any())).thenReturn(config);
        mvc.perform(put("/api/v1/blogs/marco/sidebar").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"type\":\"TAGS\",\"enabled\":true}]}"))
                .andExpect(status().isOk());
        verify(sidebarService).save(1L, "marco", config);
    }

    @Test
    void errors() throws Exception {
        when(sidebarService.config(2L, "marco")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        mvc.perform(get("/api/v1/blogs/marco/manage/sidebar").cookie(authCookies.user(2L)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/blogs/marco/manage/sidebar")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/blogs/marco/sidebar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownItemTypeIs400() throws Exception {
        mvc.perform(put("/api/v1/blogs/marco/sidebar").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"type\":\"WEATHER\",\"enabled\":true}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"));
        verifyNoInteractions(sidebarService);
    }
}
