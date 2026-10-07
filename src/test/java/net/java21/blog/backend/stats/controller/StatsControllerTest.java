package net.java21.blog.backend.stats.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.stats.dto.VisitStatsResponse;
import net.java21.blog.backend.stats.dto.VisitorCountsResponse;
import net.java21.blog.backend.stats.service.VisitorStatsService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 통계 API(T057): 주인 200, 남의 블로그 403, 비로그인 401, days 범위 밖 400. */
@WebMvcTest(StatsController.class)
@Import(WebMvcTestSupport.class)
class StatsControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private VisitorStatsService statsService;

    @Test
    void ownerGetsStats() throws Exception {
        when(statsService.stats(1L, "marco", 7)).thenReturn(new VisitStatsResponse(
                new VisitorCountsResponse(2, 3, 40),
                List.of(new VisitStatsResponse.Daily(LocalDate.of(2026, 10, 7), 2)),
                List.of(new VisitStatsResponse.TopPost(5L, "인기", 99))));
        mvc.perform(get("/api/v1/blogs/marco/manage/stats").param("days", "7").cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.visitors.total").value(40))
                .andExpect(jsonPath("$.result.daily[0].date").value("2026-10-07"))
                .andExpect(jsonPath("$.result.daily[0].visitors").value(2))
                .andExpect(jsonPath("$.result.topPosts[0].viewCount").value(99));
    }

    @Test
    void errors() throws Exception {
        when(statsService.stats(2L, "marco", null)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        mvc.perform(get("/api/v1/blogs/marco/manage/stats").cookie(authCookies.user(2L)))
                .andExpect(status().isForbidden());
        when(statsService.stats(1L, "marco", 31))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "days"));
        mvc.perform(get("/api/v1/blogs/marco/manage/stats").param("days", "31").cookie(authCookies.user(1L)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/blogs/marco/manage/stats"))
                .andExpect(status().isUnauthorized());
    }
}
