package net.java21.blog.backend.admin.external;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.AdminExternalPostResponse;
import net.java21.blog.backend.external.dto.ExternalExclusionResponse;
import net.java21.blog.backend.external.dto.MyExternalPostResponse;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 007 T073: 관리자 외부 글 내림, 포털 제외·해제 API. 관리자 외 404. */
@WebMvcTest(AdminExternalPostController.class)
@Import(WebMvcTestSupport.class)
class AdminExternalPostControllerTest {

    private static final long ADMIN = 5L;
    private static final Instant AT = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private AdminExternalPostService service;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void remove() throws Exception {
        AdminExternalPostResponse removed = new AdminExternalPostResponse(new MyExternalPostResponse(31L, "P1", "s",
                "https://remote.example/1", null, AT, 3L, TopicSource.DEFAULT, ExternalPostStatus.REMOVED,
                RemovedReason.ADMIN, 0), "g1", null, List.of(), null, BigDecimal.ONE, "kw-1", null, null);
        when(service.remove(eq(ADMIN), eq(31L), eq("저작권"), anyString())).thenReturn(removed);
        mvc.perform(post("/api/v1/admin/external-posts/31/remove").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"저작권\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result.status").value("REMOVED"))
                .andExpect(jsonPath("$.result.removedReason").value("ADMIN"));

        when(service.remove(eq(ADMIN), eq(32L), eq("x"), anyString())).thenThrow(BusinessException.withParams(
                ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT, "x", Map.of("status", "REMOVED", "action", "remove")));
        mvc.perform(post("/api/v1/admin/external-posts/32/remove").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.params.action").value("remove"));
    }

    @Test
    void excludeAndUnexclude() throws Exception {
        when(service.exclude(eq(ADMIN), eq(31L), eq("광고"), anyString()))
                .thenReturn(new ExternalExclusionResponse(31L, "광고", new AdminRef(ADMIN, "admin"), AT));
        mvc.perform(put("/api/v1/admin/portal/external-exclusions/31").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"광고\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.externalPostId").value(31))
                .andExpect(jsonPath("$.result.excludedBy.nickname").value("admin"));

        mvc.perform(delete("/api/v1/admin/portal/external-exclusions/31").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.resultCode").value("OK"));
        verify(service).unexclude(eq(ADMIN), eq(31L), anyString());

        doThrow(new BusinessException(ErrorCode.PORTAL_EXCLUSION_NOT_FOUND, "x")).when(service)
                .unexclude(eq(ADMIN), eq(33L), anyString());
        mvc.perform(delete("/api/v1/admin/portal/external-exclusions/33").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("PORTAL_EXCLUSION_NOT_FOUND"));
    }

    @Test
    void nonAdminsGet404() throws Exception {
        mvc.perform(post("/api/v1/admin/external-posts/31/remove").cookie(authCookies.user(6L))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/admin/portal/external-exclusions/31"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
