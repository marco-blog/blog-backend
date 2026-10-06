package net.java21.blog.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.portal.AdminCurationController;
import net.java21.blog.backend.admin.portal.AdminCurationService;
import net.java21.blog.backend.admin.portal.AdminExclusionController;
import net.java21.blog.backend.admin.portal.AdminExclusionService;
import net.java21.blog.backend.admin.portal.AdminPortalPostController;
import net.java21.blog.backend.admin.portal.CurationStatus;
import net.java21.blog.backend.admin.portal.dto.AdminPortalPostResponse;
import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.admin.portal.dto.CreateCurationRequest;
import net.java21.blog.backend.admin.portal.dto.CurationResponse;
import net.java21.blog.backend.admin.portal.dto.ExclusionResponse;
import net.java21.blog.backend.admin.portal.dto.PostRef;
import net.java21.blog.backend.admin.portal.dto.UpdateCurationRequest;
import net.java21.blog.backend.admin.setting.AdminSettingController;
import net.java21.blog.backend.admin.setting.AdminSettingService;
import net.java21.blog.backend.admin.setting.dto.SettingResponse;
import net.java21.blog.backend.admin.topic.AdminTopicController;
import net.java21.blog.backend.admin.topic.AdminTopicService;
import net.java21.blog.backend.admin.topic.dto.AdminTopicNode;
import net.java21.blog.backend.admin.topic.dto.CreateTopicRequest;
import net.java21.blog.backend.admin.topic.dto.TopicOrderRequest;
import net.java21.blog.backend.admin.topic.dto.UpdateTopicRequest;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 003 관리자 API(T087·T088·T097): 주제·추천·제외·글 찾기·운영 설정의 요청/응답 모양, 201+Location, 200 + result null,
 * 오류 코드(400/404/409/422), {@code Cache-Control: no-store}, 그리고 일반 회원에게 404.
 */
@WebMvcTest({AdminTopicController.class, AdminCurationController.class, AdminExclusionController.class,
        AdminPortalPostController.class, AdminSettingController.class})
@Import(WebMvcTestSupport.class)
class AdminPortalWebMvcTest {

    private static final long ADMIN = 5L;
    private static final Instant T = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private AdminTopicService topicService;
    @MockitoBean
    private AdminCurationService curationService;
    @MockitoBean
    private AdminExclusionService exclusionService;
    @MockitoBean
    private AdminSettingService settingService;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void ordinaryMembersSeeNothing() throws Exception {
        when(roleLookup.isActiveAdmin(1L)).thenReturn(false);
        for (MockHttpServletRequestBuilder request : List.of(get("/api/v1/admin/topics"),
                get("/api/v1/admin/portal/curations"), get("/api/v1/admin/portal/exclusions"),
                get("/api/v1/admin/portal/posts/1"), get("/api/v1/admin/settings"))) {
            mvc.perform(request.cookie(authCookies.user(1L)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
        }
        verifyNoInteractions(topicService, curationService, exclusionService, settingService);
    }

    @Test
    void topicTreeCreateUpdateReorder() throws Exception {
        AdminTopicNode node = new AdminTopicNode(12L, "cats", 1L, Map.of("ko", "고양이"), "#AABBCC", false, List.of(),
                false, false, true, 3, T, T);
        when(topicService.tree()).thenReturn(List.of(node));
        when(topicService.create(eq(ADMIN), any(), anyString())).thenReturn(node);
        when(topicService.update(eq(ADMIN), eq(12L), any(), anyString())).thenReturn(node);
        when(topicService.reorder(eq(ADMIN), any(), anyString())).thenReturn(List.of(node));

        mvc.perform(admin(get("/api/v1/admin/topics")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.result[0].slug").value("cats"))
                .andExpect(jsonPath("$.result[0].pinnedOnTab").value(true))
                .andExpect(jsonPath("$.result[0].recentPostCount").value(3));
        mvc.perform(admin(post("/api/v1/admin/topics"))
                        .content("{\"parentId\":1,\"slug\":\"cats\",\"names\":{\"ko\":\"고양이\"},\"cardColor\":null}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/topics/12"))
                .andExpect(jsonPath("$.result.id").value(12));
        ArgumentCaptor<CreateTopicRequest> created = ArgumentCaptor.forClass(CreateTopicRequest.class);
        verify(topicService).create(eq(ADMIN), created.capture(), eq("127.0.0.1"));
        assertThat(created.getValue().names()).containsEntry("ko", "고양이");

        mvc.perform(admin(patch("/api/v1/admin/topics/12")).content("{\"cardColor\":null,\"adminHidden\":true}"))
                .andExpect(status().isOk());
        ArgumentCaptor<UpdateTopicRequest> updated = ArgumentCaptor.forClass(UpdateTopicRequest.class);
        verify(topicService).update(eq(ADMIN), eq(12L), updated.capture(), anyString());
        assertThat(updated.getValue().hasCardColor()).isTrue();
        assertThat(updated.getValue().getCardColor()).isNull();
        assertThat(updated.getValue().hasNames()).isFalse();
        assertThat(updated.getValue().getAdminHidden()).isTrue();

        mvc.perform(admin(put("/api/v1/admin/topics/order")).content("{\"parentId\":1,\"ids\":[12,11]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(12));
        ArgumentCaptor<TopicOrderRequest> order = ArgumentCaptor.forClass(TopicOrderRequest.class);
        verify(topicService).reorder(eq(ADMIN), order.capture(), anyString());
        assertThat(order.getValue().ids()).containsExactly(12L, 11L);
    }

    @Test
    void topicErrorsKeepTheirStatus() throws Exception {
        when(topicService.create(eq(ADMIN), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.TOPIC_SLUG_TAKEN, "taken"))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                        List.of(FieldError.of("names.ja", "REQUIRED"))));
        mvc.perform(admin(post("/api/v1/admin/topics")).content("{\"slug\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("TOPIC_SLUG_TAKEN"));
        mvc.perform(admin(post("/api/v1/admin/topics")).content("{\"slug\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("names.ja"));
    }

    @Test
    void curations() throws Exception {
        CurationResponse curation = new CurationResponse(7L, new PostRef(100L, "글", "marco"), T, T.plusSeconds(60), 0,
                CurationStatus.ACTIVE, true, new AdminRef(ADMIN, "관리자"), T, T);
        when(curationService.list(eq("ACTIVE"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(curation), PageRequest.of(0, 20), 1));
        when(curationService.create(eq(ADMIN), any(), anyString())).thenReturn(curation);
        when(curationService.update(eq(ADMIN), eq(7L), any(), anyString())).thenReturn(curation);

        mvc.perform(admin(get("/api/v1/admin/portal/curations")).param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.result[0].post.blogHandle").value("marco"))
                .andExpect(jsonPath("$.result[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.result[0].createdBy.nickname").value("관리자"));
        mvc.perform(admin(post("/api/v1/admin/portal/curations"))
                        .content("{\"postId\":100,\"startsAt\":\"2026-10-06T00:00:00Z\","
                                + "\"endsAt\":\"2026-10-06T00:01:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/portal/curations/7"));
        ArgumentCaptor<CreateCurationRequest> created = ArgumentCaptor.forClass(CreateCurationRequest.class);
        verify(curationService).create(eq(ADMIN), created.capture(), anyString());
        assertThat(created.getValue().startsAt()).isEqualTo(T);
        assertThat(created.getValue().sortOrder()).isNull();

        mvc.perform(admin(patch("/api/v1/admin/portal/curations/7")).content("{\"sortOrder\":3}"))
                .andExpect(status().isOk());
        ArgumentCaptor<UpdateCurationRequest> updated = ArgumentCaptor.forClass(UpdateCurationRequest.class);
        verify(curationService).update(eq(ADMIN), eq(7L), updated.capture(), anyString());
        assertThat(updated.getValue().sortOrder()).isEqualTo(3);

        mvc.perform(admin(delete("/api/v1/admin/portal/curations/7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(curationService).delete(ADMIN, 7L, "127.0.0.1");
    }

    @Test
    void curationErrors() throws Exception {
        when(curationService.create(eq(ADMIN), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.POST_NOT_PORTAL_ELIGIBLE,
                        "Post is not portal eligible: TOO_SHORT"))
                .thenThrow(new BusinessException(ErrorCode.CURATION_LIMIT_EXCEEDED, "full"));
        mvc.perform(admin(post("/api/v1/admin/portal/curations")).content("{\"postId\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.header.resultMessage").value("Post is not portal eligible: TOO_SHORT"));
        mvc.perform(admin(post("/api/v1/admin/portal/curations")).content("{\"postId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("CURATION_LIMIT_EXCEEDED"));
        when(curationService.update(anyLong(), anyLong(), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.CURATION_NOT_FOUND, "none"));
        mvc.perform(admin(patch("/api/v1/admin/portal/curations/9")).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CURATION_NOT_FOUND"));
    }

    @Test
    void exclusionsAndPostLookup() throws Exception {
        ExclusionResponse exclusion = new ExclusionResponse(new PostRef(100L, "글", "marco"), "광고",
                new AdminRef(ADMIN, "관리자"), T, T);
        when(exclusionService.list(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(exclusion), PageRequest.of(0, 20), 1));
        when(exclusionService.exclude(ADMIN, 100L, "광고", "127.0.0.1")).thenReturn(exclusion);
        when(exclusionService.lookup(100L)).thenReturn(new AdminPortalPostResponse(100L, "글",
                new AdminPortalPostResponse.BlogRef("marco", "마르코"), PostStatus.PUBLISHED, PostVisibility.PUBLIC, T,
                false, List.of("EXCLUDED"), new AdminPortalPostResponse.Excluded("광고", new AdminRef(ADMIN, "관리자"),
                        T)));

        mvc.perform(admin(get("/api/v1/admin/portal/exclusions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.result[0].reason").value("광고"));
        mvc.perform(admin(put("/api/v1/admin/portal/exclusions/100")).content("{\"reason\":\"광고\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.post.id").value(100));
        mvc.perform(admin(delete("/api/v1/admin/portal/exclusions/100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(exclusionService).unexclude(ADMIN, 100L, "127.0.0.1");
        mvc.perform(admin(get("/api/v1/admin/portal/posts/100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.portalEligible").value(false))
                .andExpect(jsonPath("$.result.ineligibleReasons[0]").value("EXCLUDED"))
                .andExpect(jsonPath("$.result.excluded.excludedBy.nickname").value("관리자"))
                .andExpect(jsonPath("$.result.blog.handle").value("marco"));

        when(exclusionService.lookup(5L)).thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "none"));
        mvc.perform(admin(get("/api/v1/admin/portal/posts/5")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"));
    }

    @Test
    void settings() throws Exception {
        SettingResponse min = new SettingResponse("portal.min-content-length", 300, 200, true,
                new AdminRef(ADMIN, "관리자"), T);
        when(settingService.list("portal.")).thenReturn(List.of(min));
        when(settingService.set(ADMIN, "portal.min-content-length", 300, "127.0.0.1")).thenReturn(min);
        when(settingService.reset(ADMIN, "portal.min-content-length", "127.0.0.1"))
                .thenReturn(new SettingResponse("portal.min-content-length", 200, 200, false, null, null));
        when(settingService.set(eq(ADMIN), eq("portal.score-weights"), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                        List.of(new FieldError("value.likeWeight", "TOO_LARGE", Map.of("max", 1000)))));
        when(settingService.set(eq(ADMIN), eq("nope"), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.SETTING_NOT_FOUND, "none"));

        mvc.perform(admin(get("/api/v1/admin/settings")).param("prefix", "portal."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].key").value("portal.min-content-length"))
                .andExpect(jsonPath("$.result[0].overridden").value(true));
        mvc.perform(admin(put("/api/v1/admin/settings/portal.min-content-length")).content("{\"value\":300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.value").value(300))
                .andExpect(jsonPath("$.result.defaultValue").value(200));
        mvc.perform(admin(delete("/api/v1/admin/settings/portal.min-content-length")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.overridden").value(false));
        mvc.perform(admin(put("/api/v1/admin/settings/portal.score-weights"))
                        .content("{\"value\":{\"likeWeight\":5000}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("value.likeWeight"));
        mvc.perform(admin(put("/api/v1/admin/settings/nope")).content("{\"value\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SETTING_NOT_FOUND"));
        verify(settingService).list("portal.");
        verify(settingService).reset(ADMIN, "portal.min-content-length", "127.0.0.1");
        when(settingService.list(isNull())).thenReturn(List.of());
        mvc.perform(admin(get("/api/v1/admin/settings"))).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.cookie(authCookies.user(ADMIN)).contentType(MediaType.APPLICATION_JSON);
    }
}
