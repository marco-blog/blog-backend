package net.java21.blog.backend.admin.external;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RegistrationType;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.AdminExternalBlogResponse;
import net.java21.blog.backend.external.dto.AdminExternalPostResponse;
import net.java21.blog.backend.external.dto.MyExternalBlogResponse;
import net.java21.blog.backend.external.dto.MyExternalPostResponse;
import net.java21.blog.backend.external.feed.FeedFormat;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
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

/** 007 T030: 관리자 외부 블로그 API — 목록·직접 등록 201·조회·PATCH·승인·거절·글, 검증 오류, 관리자 외 404. */
@WebMvcTest(AdminExternalBlogController.class)
@Import(WebMvcTestSupport.class)
class AdminExternalBlogControllerTest {

    private static final long ADMIN = 5L;
    private static final Instant AT = Instant.parse("2026-10-07T00:00:00Z");
    private static final String FEED = "https://remote.example/feed";
    private static final AdminExternalBlogResponse BLOG = new AdminExternalBlogResponse(
            new MyExternalBlogResponse(11L, "Remote", "https://remote.example/", FEED, FeedFormat.RSS,
                    ExternalBlogStatus.PENDING, RegistrationType.MEMBER_REQUEST, true, 3L, null, null, null, null, 2,
                    AT),
            new AdminExternalBlogResponse.Member(7L, "member", UserStatus.ACTIVE), null, new AdminRef(ADMIN, "admin"),
            AT, AT, null, null, 0, null, 1);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private AdminExternalBlogService service;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void listAndGet() throws Exception {
        when(service.list(eq(ExternalBlogStatus.PENDING), eq("remote"), any()))
                .thenReturn(new PageImpl<>(List.of(BLOG), PageRequest.of(0, 1), 4));
        mvc.perform(get("/api/v1/admin/external-blogs?status=PENDING&q=remote&size=1")
                .cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.result[0].id").value(11))
                .andExpect(jsonPath("$.result[0].ownershipVerified").value(true))
                .andExpect(jsonPath("$.result[0].member.nickname").value("member"))
                .andExpect(jsonPath("$.result[0].reviewedBy.nickname").value("admin"))
                .andExpect(jsonPath("$.result[0].pendingReviewCount").value(1));

        when(service.get(11L)).thenReturn(BLOG);
        mvc.perform(get("/api/v1/admin/external-blogs/11").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.feedUrl").value(FEED))
                .andExpect(jsonPath("$.result.postCount").value(2));

        mvc.perform(get("/api/v1/admin/external-blogs?status=NOPE").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns201() throws Exception {
        when(service.create(eq(ADMIN), eq(FEED), eq(3L), eq("good blog"), anyString())).thenReturn(BLOG);
        mvc.perform(post("/api/v1/admin/external-blogs").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"feedUrl\":\"" + FEED + "\",\"defaultTopicId\":3,\"registrationBasis\":\"good blog\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/external-blogs/11"))
                .andExpect(jsonPath("$.result.id").value(11));

        when(service.create(eq(ADMIN), eq(FEED), eq(3L), isNull(), anyString())).thenThrow(
                new BusinessException(ErrorCode.VALIDATION_FAILED, "x",
                        List.of(FieldError.of("registrationBasis", "REQUIRED"))));
        mvc.perform(post("/api/v1/admin/external-blogs").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"feedUrl\":\"" + FEED + "\",\"defaultTopicId\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("registrationBasis"));
    }

    @Test
    void patchApproveRejectAndPosts() throws Exception {
        when(service.updateDefaultTopic(eq(ADMIN), eq(11L), eq(4L), anyString())).thenReturn(BLOG);
        mvc.perform(patch("/api/v1/admin/external-blogs/11").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"defaultTopicId\":4}"))
                .andExpect(status().isOk());

        when(service.approve(eq(ADMIN), eq(11L), anyString())).thenReturn(BLOG);
        mvc.perform(post("/api/v1/admin/external-blogs/11/approve").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(11));
        when(service.approve(eq(ADMIN), eq(12L), anyString())).thenThrow(BusinessException.withParams(
                ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT, "x", java.util.Map.of("status", "ACTIVE", "action", "approve")));
        mvc.perform(post("/api/v1/admin/external-blogs/12/approve").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.params.status").value("ACTIVE"));

        when(service.reject(eq(ADMIN), eq(11L), eq("off topic"), anyString())).thenReturn(BLOG);
        mvc.perform(post("/api/v1/admin/external-blogs/11/reject").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"off topic\"}"))
                .andExpect(status().isOk());

        AdminExternalPostResponse post = new AdminExternalPostResponse(new MyExternalPostResponse(31L, "P1", "s",
                "https://remote.example/1", null, AT, 3L, TopicSource.DEFAULT, ExternalPostStatus.ACTIVE, null, 0),
                "g1", "https://remote.example/a.png", List.of("java"), null, BigDecimal.valueOf(0.5), "kw-1",
                new AdminExternalPostResponse.Excluded("spam", new AdminRef(ADMIN, "admin"), AT), null);
        when(service.posts(eq(11L), eq(ExternalPostStatus.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of(post), PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/v1/admin/external-blogs/11/posts?status=ACTIVE").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(31))
                .andExpect(jsonPath("$.result[0].guid").value("g1"))
                .andExpect(jsonPath("$.result[0].feedTerms[0]").value("java"))
                .andExpect(jsonPath("$.result[0].excluded.reason").value("spam"));
    }

    @Test
    void nonAdminsGet404() throws Exception {
        mvc.perform(get("/api/v1/admin/external-blogs").cookie(authCookies.user(6L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
        mvc.perform(post("/api/v1/admin/external-blogs/1/approve"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
