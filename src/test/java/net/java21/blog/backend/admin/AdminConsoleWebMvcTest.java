package net.java21.blog.backend.admin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.audit.AdminAuditLogController;
import net.java21.blog.backend.admin.audit.AdminAuditLogService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.audit.dto.AuditActionListResponse;
import net.java21.blog.backend.admin.audit.dto.AuditLogDetailResponse;
import net.java21.blog.backend.admin.audit.dto.AuditLogEntryResponse;
import net.java21.blog.backend.admin.content.AdminContentSearchController;
import net.java21.blog.backend.admin.content.AdminContentSearchService;
import net.java21.blog.backend.admin.content.dto.AdminCommentRow;
import net.java21.blog.backend.admin.content.dto.AdminGuestbookRow;
import net.java21.blog.backend.admin.content.dto.AdminPostRow;
import net.java21.blog.backend.admin.content.dto.ContentAuthor;
import net.java21.blog.backend.admin.dashboard.AdminDashboardController;
import net.java21.blog.backend.admin.dashboard.AdminDashboardService;
import net.java21.blog.backend.admin.dashboard.dto.AdminDashboardResponse;
import net.java21.blog.backend.admin.user.AdminRoleController;
import net.java21.blog.backend.admin.user.AdminRoleService;
import net.java21.blog.backend.admin.user.dto.AdminMemberResponse;
import net.java21.blog.backend.admin.user.dto.RoleChangeRequest;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 006 T021·T024·T041·T044: 대시보드·콘텐츠 검색·작업 기록·관리자 권한 API의 응답 모양, 페이지(0부터·기본 20·최대 50),
 * {@code Cache-Control: no-store}, 작업 기록 수정·삭제 405, 오류 코드, 요청 IP 전달, 일반 회원 404.
 */
@WebMvcTest({AdminDashboardController.class, AdminContentSearchController.class, AdminAuditLogController.class,
        AdminRoleController.class})
@Import(WebMvcTestSupport.class)
class AdminConsoleWebMvcTest {

    private static final long ADMIN = 5L;
    private static final Instant T = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private AdminDashboardService dashboardService;
    @MockitoBean
    private AdminContentSearchService contentService;
    @MockitoBean
    private AdminAuditLogService auditService;
    @MockitoBean
    private AdminRoleService roleService;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void dashboard() throws Exception {
        when(dashboardService.dashboard(ADMIN)).thenReturn(new AdminDashboardResponse(
                new AdminDashboardResponse.Today(1, 2, 3), new AdminDashboardResponse.Totals(10, 4, 7), null,
                List.of(new AdminDashboardResponse.TrendDay(LocalDate.of(2026, 10, 7), 1, 2)), "Asia/Seoul", T));
        mvc.perform(admin(get("/api/v1/admin/dashboard")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result.today.comments").value(3))
                .andExpect(jsonPath("$.result.totals.publicPosts").value(7))
                .andExpect(jsonPath("$.result.pendingReports").value(nullValue()))
                .andExpect(jsonPath("$.result.trend[0].date").value("2026-10-07"))
                .andExpect(jsonPath("$.result.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.result.generatedAt").value("2026-10-07T00:00:00Z"));
    }

    @Test
    void membersAreHidden() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
        verifyNoInteractions(dashboardService);
    }

    @Test
    void contentSearchPagesAndRows() throws Exception {
        ContentAuthor author = new ContentAuthor(9L, "작성자", UserStatus.ACTIVE);
        when(contentService.posts(eq("자바"), eq("blog"), eq(9L), eq("PUBLISHED"), eq("PUBLIC"), any()))
                .thenReturn(new PageImpl<>(List.of(new AdminPostRow(1L, "자바 글",
                        new AdminPostRow.BlogRef("blog", "블로그", BlogStatus.ACTIVE), author, PostStatus.PUBLISHED,
                        PostVisibility.PUBLIC, T, T, null, 2)), PageRequest.of(1, 50), 51));
        mvc.perform(admin(get("/api/v1/admin/contents/posts")).param("q", "자바").param("handle", "blog")
                        .param("authorId", "9").param("status", "PUBLISHED").param("visibility", "PUBLIC")
                        .param("page", "1").param("size", "80"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.totalCount").value(51))
                .andExpect(jsonPath("$.result[0].blog.handle").value("blog"))
                .andExpect(jsonPath("$.result[0].author.nickname").value("작성자"))
                .andExpect(jsonPath("$.result[0].commentCount").value(2))
                .andExpect(jsonPath("$.result[0].deletedAt").value(nullValue()));
        verify(contentService).posts(eq("자바"), eq("blog"), eq(9L), eq("PUBLISHED"), eq("PUBLIC"),
                eq(PageRequest.of(1, 50)));

        when(contentService.comments(eq(3L), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(
                new AdminCommentRow(4L, 3L, "글", "blog", null, null, "손님", true, null, CommentStatus.ACTIVE, T))));
        mvc.perform(admin(get("/api/v1/admin/contents/comments")).param("postId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].author").value(nullValue()))
                .andExpect(jsonPath("$.result[0].guestName").value("손님"))
                .andExpect(jsonPath("$.result[0].content").value(nullValue()))
                .andExpect(jsonPath("$.result[0].guestIp").doesNotExist());
        verify(contentService).comments(eq(3L), eq(null), eq(null), eq(null), eq(null), eq(PageRequest.of(0, 20)));

        when(contentService.guestbook(any(), any(), any(), eq("키워드"), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "scope",
                        List.of(new FieldError("q", "INVALID", Map.of("reason", "SCOPE_REQUIRED")))));
        mvc.perform(admin(get("/api/v1/admin/contents/guestbook-entries")).param("q", "키워드"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("q"))
                .andExpect(jsonPath("$.header.fieldErrors[0].params.reason").value("SCOPE_REQUIRED"));
        when(contentService.guestbook(eq("blog"), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(
                new AdminGuestbookRow(6L, "blog", null, null, "손님", false, "안녕", GuestbookStatus.ACTIVE, T))));
        mvc.perform(admin(get("/api/v1/admin/contents/guestbook-entries")).param("handle", "blog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].content").value("안녕"));
    }

    @Test
    void auditLogsAreReadOnly() throws Exception {
        AuditLogEntryResponse entry = new AuditLogEntryResponse(3L, new AuditLogEntryResponse.AdminName(ADMIN, "관리자"),
                AuditActions.ROLE_GRANT, AuditActions.TARGET_USER, 7L, null, Map.of("role", "USER"),
                Map.of("role", "ADMIN"), null, T);
        when(auditService.list(eq(ADMIN), any(), any())).thenReturn(new PageImpl<>(List.of(entry)));
        mvc.perform(admin(get("/api/v1/admin/audit-logs")).param("from", "2026-10-01").param("action",
                        "ROLE_GRANT,ROLE_REVOKE").param("targetType", "USER").param("targetId", "7"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result[0].admin.nickname").value("관리자"))
                .andExpect(jsonPath("$.result[0].after.role").value("ADMIN"))
                .andExpect(jsonPath("$.result[0].requestIp").doesNotExist());
        verify(auditService).list(eq(ADMIN), eq(new AdminAuditLogService.Query("2026-10-01", null, null,
                "ROLE_GRANT,ROLE_REVOKE", "USER", 7L, null)), eq(PageRequest.of(0, 20)));

        when(auditService.detail(ADMIN, 3L)).thenReturn(new AuditLogDetailResponse(3L, entry.admin(), entry.action(),
                entry.targetType(), 7L, null, entry.before(), entry.after(), null, T, null));
        mvc.perform(admin(get("/api/v1/admin/audit-logs/3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.requestIp").value(nullValue()));
        when(auditService.actions()).thenReturn(new AuditActionListResponse(AuditActions.ALL, AuditActions.TARGETS));
        mvc.perform(admin(get("/api/v1/admin/audit-logs/actions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.targetTypes[0]").value(AuditActions.TARGETS.get(0)));

        for (MockHttpServletRequestBuilder write : List.of(put("/api/v1/admin/audit-logs/3"),
                patch("/api/v1/admin/audit-logs/3"), delete("/api/v1/admin/audit-logs/3"),
                delete("/api/v1/admin/audit-logs"))) {
            mvc.perform(admin(write).header("Origin", WebMvcTestSupport.ALLOWED_ORIGIN).content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Test
    void roles() throws Exception {
        when(roleService.admins()).thenReturn(List.of(new AdminMemberResponse(1L, "최고", UserRole.SUPER_ADMIN,
                UserStatus.ACTIVE, T), new AdminMemberResponse(2L, "관리", UserRole.ADMIN, UserStatus.SUSPENDED, T)));
        mvc.perform(admin(get("/api/v1/admin/admins")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result[0].role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.result[1].status").value("SUSPENDED"));

        when(roleService.changeRole(eq(ADMIN), eq(7L), any(), any())).thenReturn(new AdminMemberResponse(7L, "회원",
                UserRole.ADMIN, UserStatus.ACTIVE, T));
        mvc.perform(admin(put("/api/v1/admin/users/7/role")).header("Origin", WebMvcTestSupport.ALLOWED_ORIGIN)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.role").value("ADMIN"))
                .andExpect(jsonPath("$.result.userId").value(7));
        verify(roleService).changeRole(ADMIN, 7L, new RoleChangeRequest("ADMIN"), "127.0.0.1");

        for (ErrorCode code : List.of(ErrorCode.FORBIDDEN, ErrorCode.USER_NOT_FOUND, ErrorCode.CANNOT_CHANGE_OWN_ROLE,
                ErrorCode.USER_NOT_ACTIVE, ErrorCode.LAST_SUPER_ADMIN)) {
            org.mockito.Mockito.doThrow(new BusinessException(code, "x")).when(roleService)
                    .changeRole(eq(ADMIN), eq(8L), any(), any());
            mvc.perform(admin(put("/api/v1/admin/users/8/role")).header("Origin", WebMvcTestSupport.ALLOWED_ORIGIN)
                            .content("{\"role\":\"USER\"}"))
                    .andExpect(status().is(code.status().value()))
                    .andExpect(jsonPath("$.header.resultCode").value(code.name()));
        }
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.cookie(authCookies.user(ADMIN)).contentType(MediaType.APPLICATION_JSON);
    }
}
