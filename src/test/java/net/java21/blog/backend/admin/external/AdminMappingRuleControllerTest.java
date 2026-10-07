package net.java21.blog.backend.admin.external;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.dto.TopicMappingRuleResponse;
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

/** 007 T064: 매핑 규칙 목록·추가 201·수정·삭제 API — 검증·오류 코드·{@code no-store}, 관리자 외 404. */
@WebMvcTest(AdminMappingRuleController.class)
@Import(WebMvcTestSupport.class)
class AdminMappingRuleControllerTest {

    private static final long ADMIN = 5L;
    private static final Instant AT = Instant.parse("2026-10-07T00:00:00Z");
    private static final TopicMappingRuleResponse RULE = new TopicMappingRuleResponse(51L, "spring", 4L, 10,
            new AdminRef(ADMIN, "admin"), AT, AT);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private AdminMappingRuleService service;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void listCreateUpdateDelete() throws Exception {
        when(service.list(eq("spr"), any())).thenReturn(new PageImpl<>(List.of(RULE), PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/v1/admin/topic-mapping-rules?q=spr").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.result[0].keyword").value("spring"))
                .andExpect(jsonPath("$.result[0].createdBy.nickname").value("admin"));

        when(service.create(eq(ADMIN), eq("Spring"), eq(4L), isNull(), anyString())).thenReturn(RULE);
        mvc.perform(post("/api/v1/admin/topic-mapping-rules").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"Spring\",\"topicId\":4}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/topic-mapping-rules/51"))
                .andExpect(jsonPath("$.result.priority").value(10));

        when(service.create(eq(ADMIN), eq("spring"), eq(4L), isNull(), anyString())).thenThrow(
                BusinessException.withParams(ErrorCode.TOPIC_MAPPING_RULE_KEYWORD_TAKEN, "x", Map.of("ruleId", 51)));
        mvc.perform(post("/api/v1/admin/topic-mapping-rules").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"spring\",\"topicId\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.params.ruleId").value(51));

        when(service.create(eq(ADMIN), isNull(), eq(4L), isNull(), anyString())).thenThrow(
                new BusinessException(ErrorCode.VALIDATION_FAILED, "x", List.of(FieldError.of("keyword", "REQUIRED"))));
        mvc.perform(post("/api/v1/admin/topic-mapping-rules").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"topicId\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("keyword"));

        when(service.update(eq(ADMIN), eq(51L), isNull(), isNull(), eq(20), anyString())).thenReturn(RULE);
        mvc.perform(patch("/api/v1/admin/topic-mapping-rules/51").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":20}"))
                .andExpect(status().isOk());
        when(service.update(eq(ADMIN), eq(52L), isNull(), isNull(), eq(20), anyString())).thenThrow(
                new BusinessException(ErrorCode.NOT_FOUND, "x"));
        mvc.perform(patch("/api/v1/admin/topic-mapping-rules/52").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":20}"))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/admin/topic-mapping-rules/51").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service).delete(eq(ADMIN), eq(51L), anyString());
    }

    @Test
    void nonAdminGets404() throws Exception {
        mvc.perform(get("/api/v1/admin/topic-mapping-rules").cookie(authCookies.user(6L)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
