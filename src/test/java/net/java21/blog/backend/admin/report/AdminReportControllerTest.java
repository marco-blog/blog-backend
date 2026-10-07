package net.java21.blog.backend.admin.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.admin.report.dto.AssignTargetRequest;
import net.java21.blog.backend.admin.report.dto.ReportDetailResponse;
import net.java21.blog.backend.admin.report.dto.ReportGroupResponse;
import net.java21.blog.backend.admin.report.dto.ReportSummaryResponse;
import net.java21.blog.backend.admin.report.dto.ResolveReportRequest;
import net.java21.blog.backend.admin.report.dto.ResolveReportResponse;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
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

/** 005 T041: 관리자 신고 API 5개. 목록 페이지, 요약, 상세, 대상 지정, 처리, 오류 코드, 일반 회원 404, no-store. */
@WebMvcTest(AdminReportController.class)
@Import(WebMvcTestSupport.class)
class AdminReportControllerTest {

    private static final long ADMIN = 5L;
    private static final Instant T = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private AdminReportService service;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void listAndSummary() throws Exception {
        ReportTargetPreview preview = new ReportTargetPreview(ReportTargetType.POST, 9L,
                ReportTargetPreview.State.ACTIVE, "글", "요약", "https://blog.java21.net/marco/9",
                new ReportTargetPreview.Author(1L, "marco", false), new ReportTargetPreview.BlogRef("marco", "M"));
        when(service.list(eq("PENDING"), eq(null), eq("MEMBER"), any())).thenReturn(new PageImpl<>(List.of(
                new ReportGroupResponse(3L, ReportTargetType.POST, 9L, "MEMBER", 2,
                        List.of(new ReportGroupResponse.ReasonCount(ReportReason.SPAM, 2)), T, T,
                        ReportStatus.PENDING, null, preview)), PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/v1/admin/reports?status=PENDING&channel=MEMBER").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.result[0].representativeId").value(3))
                .andExpect(jsonPath("$.result[0].reasons[0].reason").value("SPAM"))
                .andExpect(jsonPath("$.result[0].target.state").value("ACTIVE"))
                .andExpect(jsonPath("$.totalCount").value(1));

        when(service.summary()).thenReturn(new ReportSummaryResponse(4));
        mvc.perform(get("/api/v1/admin/reports/summary").cookie(authCookies.user(ADMIN)))
                .andExpect(jsonPath("$.result.pendingCount").value(4));
    }

    @Test
    void detailAssignAndResolve() throws Exception {
        ReportDetailResponse detail = new ReportDetailResponse(3L, ReportChannel.RIGHTS_REQUEST, ReportStatus.PENDING,
                null, null, null, null, "https://x.example/a", "근거", "me@example.com", null,
                List.of(new ReportDetailResponse.ReportItem(3L, ReportChannel.RIGHTS_REQUEST, null,
                        ReportReason.COPYRIGHT, null, T)), null);
        when(service.detail(3L)).thenReturn(detail);
        mvc.perform(get("/api/v1/admin/reports/3").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contactEmail").value("me@example.com"))
                .andExpect(jsonPath("$.result.reports[0].reason").value("COPYRIGHT"));

        when(service.assignTarget(ADMIN, 3L, new AssignTargetRequest("POST", 9L))).thenReturn(detail);
        mvc.perform(patch("/api/v1/admin/reports/3/target").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"targetType\":\"POST\",\"targetId\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(3));

        when(service.resolve(eq(ADMIN), eq(3L), eq(new ResolveReportRequest("ACTION", "HIDE_CONTENT", "광고", null)),
                any())).thenReturn(new ResolveReportResponse(2, ReportStatus.ACTIONED, ReportAction.HIDE_CONTENT));
        mvc.perform(post("/api/v1/admin/reports/3/resolve").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"ACTION\",\"action\":\"HIDE_CONTENT\",\"note\":\"광고\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resolvedCount").value(2))
                .andExpect(jsonPath("$.result.decision").value("ACTIONED"))
                .andExpect(jsonPath("$.result.action").value("HIDE_CONTENT"));
    }

    @Test
    void errorsAndMembers() throws Exception {
        when(service.detail(anyLong())).thenThrow(new BusinessException(ErrorCode.REPORT_NOT_FOUND, "x"));
        mvc.perform(get("/api/v1/admin/reports/99").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("REPORT_NOT_FOUND"));
        when(service.resolve(anyLong(), anyLong(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.REPORT_ALREADY_RESOLVED, "x"));
        mvc.perform(post("/api/v1/admin/reports/3/resolve").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"DISMISS\"}"))
                .andExpect(status().isConflict());

        when(roleLookup.isActiveAdmin(1L)).thenReturn(false);
        mvc.perform(get("/api/v1/admin/reports").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
    }

    @Test
    void membersNeverReachTheService() throws Exception {
        mvc.perform(get("/api/v1/admin/reports/summary").cookie(authCookies.user(2L)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
