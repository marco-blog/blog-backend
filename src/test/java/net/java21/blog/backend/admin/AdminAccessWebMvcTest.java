package net.java21.blog.backend.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import net.java21.blog.backend.admin.user.AdminUserController;
import net.java21.blog.backend.admin.user.AdminUserService;
import net.java21.blog.backend.admin.user.dto.BlogLimitRequest;
import net.java21.blog.backend.admin.user.dto.BlogLimitResponse;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 관리자 API 접근 규칙(T152, contracts/api.md "관리자 API 공통 규칙", 006 FR-097). {@code /api/v1/admin/**}는 요청마다 DB의
 * role·status를 다시 읽고, 비로그인·일반 회원·권한이 회수된 회원은 404 {@code NOT_FOUND}다. JWT의 role 클레임은 무시한다.
 */
@WebMvcTest(AdminUserController.class)
@Import(WebMvcTestSupport.class)
class AdminAccessWebMvcTest {

    private static final String BLOG_LIMIT = "/api/v1/admin/users/7/blog-limit";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private AdminUserService adminUserService;

    @Test
    void anonymousGets404NotFound() throws Exception {
        expectHidden(mvc.perform(patchLimit("{\"maxBlogs\":0}")));
        expectHidden(mvc.perform(get("/api/v1/admin/users")));
        expectHidden(mvc.perform(get("/api/v1/admin")));
        verifyNoInteractions(roleLookup, adminUserService);
    }

    @Test
    void ordinaryMemberGets404EvenWithAdminRoleClaim() throws Exception {
        when(roleLookup.isActiveAdmin(1L)).thenReturn(false);

        expectHidden(mvc.perform(patchLimit("{\"maxBlogs\":0}").cookie(authCookies.user(1L))));
        expectHidden(mvc.perform(patchLimit("{\"maxBlogs\":0}").cookie(authCookies.of(1L, "SUPER_ADMIN"))));
        verifyNoInteractions(adminUserService);
    }

    @Test
    void revokedAdminIsRejectedOnTheNextRequest() throws Exception {
        when(roleLookup.isActiveAdmin(3L)).thenReturn(true, false);
        when(adminUserService.changeBlogLimit(anyLong(), anyLong(), any(), anyString()))
                .thenReturn(new BlogLimitResponse(7L, 2, 0, 0));

        mvc.perform(patchLimit("{\"maxBlogs\":0}").cookie(authCookies.of(3L, "ADMIN")))
                .andExpect(status().isOk());
        expectHidden(mvc.perform(patchLimit("{\"maxBlogs\":0}").cookie(authCookies.of(3L, "ADMIN"))));
    }

    @Test
    void databaseRoleWinsOverUserClaim() throws Exception {
        when(roleLookup.isActiveAdmin(5L)).thenReturn(true);
        when(adminUserService.changeBlogLimit(eq(5L), eq(7L), any(), anyString()))
                .thenReturn(new BlogLimitResponse(7L, 4, null, 3));

        mvc.perform(patchLimit("{\"maxBlogs\":null}").cookie(authCookies.user(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result.userId").value(7))
                .andExpect(jsonPath("$.result.blogCount").value(4))
                .andExpect(jsonPath("$.result.maxBlogs").isEmpty())
                .andExpect(jsonPath("$.result.effectiveLimit").value(3));

        ArgumentCaptor<BlogLimitRequest> request = ArgumentCaptor.forClass(BlogLimitRequest.class);
        verify(adminUserService).changeBlogLimit(eq(5L), eq(7L), request.capture(), eq("127.0.0.1"));
        assertThat(request.getValue().hasMaxBlogs()).isTrue();
        assertThat(request.getValue().getMaxBlogs()).isNull();
    }

    @Test
    void percentEncodedPathIsStillGuarded() throws Exception {
        when(roleLookup.isActiveAdmin(1L)).thenReturn(false);

        expectHidden(mvc.perform(patch(URI.create("/api/v1/%61dmin/users/7/blog-limit"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxBlogs\":0}").cookie(authCookies.user(1L))));
        verifyNoInteractions(adminUserService);
    }

    @Test
    void adminRequestValidation() throws Exception {
        when(roleLookup.isActiveAdmin(5L)).thenReturn(true);

        mvc.perform(patchLimit("{\"maxBlogs\":-1}").cookie(authCookies.user(5L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("maxBlogs"));
        mvc.perform(patchLimit("{\"maxBlogs\":\"many\"}").cookie(authCookies.user(5L)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adminUserService);
    }

    @Test
    void adminSeesNormalErrors() throws Exception {
        when(roleLookup.isActiveAdmin(5L)).thenReturn(true);
        when(adminUserService.changeBlogLimit(anyLong(), anyLong(), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "User not found"));

        mvc.perform(patchLimit("{\"maxBlogs\":2}").cookie(authCookies.user(5L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
    }

    @Test
    void nonAdminPathsAreNotChecked() throws Exception {
        mvc.perform(get("/api/v1/administrators").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(roleLookup);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchLimit(
            String body) {
        return patch(BLOG_LIMIT).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void expectHidden(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.result").isEmpty());
    }
}
