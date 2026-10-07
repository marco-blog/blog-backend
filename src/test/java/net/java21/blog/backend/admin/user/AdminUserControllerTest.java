package net.java21.blog.backend.admin.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.admin.user.dto.AdminUserDetail;
import net.java21.blog.backend.admin.user.dto.AdminUserSummary;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
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

/** 005 T041: 관리자 회원 검색·상세·정지·해제. 응답에 이메일이 없고 정지·해제는 상세를 돌려준다. */
@WebMvcTest(AdminUserController.class)
@Import(WebMvcTestSupport.class)
class AdminUserControllerTest {

    private static final long ADMIN = 5L;
    private static final Instant T = Instant.parse("2026-10-07T00:00:00Z");
    private static final AdminUserDetail DETAIL = new AdminUserDetail(7L, "marco", UserStatus.SUSPENDED,
            UserRole.USER, T, 1, 3, 2, T, List.of(new AdminUserDetail.BlogItem("marco", "M", BlogStatus.ACTIVE)),
            new AdminUserDetail.BlogLimit(1, 3, false));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private AdminUserService adminUserService;
    @MockitoBean
    private SuspensionService suspensionService;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void searchAndDetail() throws Exception {
        when(adminUserService.search(eq("mar"), eq("nickname"), any())).thenReturn(new PageImpl<>(List.of(
                new AdminUserSummary(7L, "marco", UserStatus.ACTIVE, UserRole.USER, T, 1)), PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/v1/admin/users?q=mar&by=nickname").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].nickname").value("marco"))
                .andExpect(jsonPath("$.result[0].email").doesNotExist())
                .andExpect(jsonPath("$.totalCount").value(1));

        when(adminUserService.detail(7L)).thenReturn(DETAIL);
        mvc.perform(get("/api/v1/admin/users/7").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.receivedReportCount").value(2))
                .andExpect(jsonPath("$.result.blogs[0].handle").value("marco"))
                .andExpect(jsonPath("$.result.blogLimit.limit").value(3))
                .andExpect(jsonPath("$.result.email").doesNotExist());
    }

    @Test
    void suspendAndUnsuspendReturnTheDetail() throws Exception {
        when(adminUserService.detail(7L)).thenReturn(DETAIL);
        mvc.perform(post("/api/v1/admin/users/7/suspend").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"스팸\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("SUSPENDED"));
        verify(suspensionService).suspend(eq(ADMIN), eq(7L), eq("스팸"), anyString());

        mvc.perform(post("/api/v1/admin/users/7/unsuspend").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk());
        verify(suspensionService).unsuspend(eq(ADMIN), eq(7L), isNull(), anyString());
    }

    @Test
    void errorCodes() throws Exception {
        when(suspensionService.suspend(anyLong(), anyLong(), any(), anyString())).thenThrow(
                new BusinessException(ErrorCode.CANNOT_SUSPEND_SELF, "self"),
                new BusinessException(ErrorCode.LAST_SUPER_ADMIN, "last"),
                new BusinessException(ErrorCode.FORBIDDEN, "no"));
        int[] expected = {422, 409, 403};
        for (int code : expected) {
            mvc.perform(post("/api/v1/admin/users/7/suspend").cookie(authCookies.user(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                    .andExpect(status().is(code));
        }
        when(adminUserService.detail(99L)).thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND, "x"));
        mvc.perform(get("/api/v1/admin/users/99").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("USER_NOT_FOUND"));
    }
}
