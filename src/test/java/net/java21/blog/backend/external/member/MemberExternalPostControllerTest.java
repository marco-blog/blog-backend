package net.java21.blog.backend.external.member;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RegistrationType;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.MyExternalBlogResponse;
import net.java21.blog.backend.external.dto.MyExternalPostResponse;
import net.java21.blog.backend.external.feed.FeedFormat;
import net.java21.blog.backend.external.verify.VerificationService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
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

/** 007 T064: 회원 글 목록·기본 주제 변경·글 주제 변경 API — 검증·오류 코드·{@code no-store}, 비로그인 401. */
@WebMvcTest(MemberExternalBlogController.class)
@Import(WebMvcTestSupport.class)
class MemberExternalPostControllerTest {

    private static final long USER = 7L;
    private static final Instant AT = Instant.parse("2026-10-07T00:00:00Z");
    private static final MyExternalBlogResponse BLOG = new MyExternalBlogResponse(11L, "Remote",
            "https://remote.example/", "https://remote.example/feed", FeedFormat.RSS, ExternalBlogStatus.ACTIVE,
            RegistrationType.MEMBER_REQUEST, true, 4L, null, null, null, null, 3, AT);
    private static final MyExternalPostResponse POST = new MyExternalPostResponse(31L, "Title", "Summary",
            "https://remote.example/1", null, AT, 4L, TopicSource.OWNER, ExternalPostStatus.ACTIVE, null, 2);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private PreviewService previewService;
    @MockitoBean
    private VerificationService verificationService;
    @MockitoBean
    private MemberExternalBlogService service;
    @MockitoBean
    private ExternalPostTopicService topicService;

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.cookie(authCookies.user(USER)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void listsPostsPaged() throws Exception {
        when(service.posts(eq(USER), eq(11L), any())).thenReturn(new PageImpl<>(List.of(POST), PageRequest.of(0, 1),
                5));
        mvc.perform(get("/api/v1/me/external-blogs/11/posts?size=1").cookie(authCookies.user(USER)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.totalCount").value(5))
                .andExpect(jsonPath("$.result[0].topicSource").value("OWNER"))
                .andExpect(jsonPath("$.result[0].clickCount").value(2));
    }

    @Test
    void changesDefaultTopic() throws Exception {
        when(topicService.changeDefaultTopic(USER, 11L, 4L)).thenReturn(BLOG);
        mvc.perform(json(patch("/api/v1/me/external-blogs/11"), "{\"defaultTopicId\":4}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result.defaultTopicId").value(4));

        when(topicService.changeDefaultTopic(USER, 12L, 4L)).thenThrow(
                new BusinessException(ErrorCode.EXTERNAL_BLOG_OWNERSHIP_REQUIRED, "x"));
        mvc.perform(json(patch("/api/v1/me/external-blogs/12"), "{\"defaultTopicId\":4}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_BLOG_OWNERSHIP_REQUIRED"));

        when(topicService.changeDefaultTopic(eq(USER), eq(11L), isNull())).thenThrow(
                new BusinessException(ErrorCode.VALIDATION_FAILED, "x",
                        List.of(FieldError.of("defaultTopicId", "REQUIRED"))));
        mvc.perform(json(patch("/api/v1/me/external-blogs/11"), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("defaultTopicId"));
    }

    @Test
    void changesPostTopic() throws Exception {
        when(topicService.changePostTopic(USER, 11L, 31L, 4L)).thenReturn(POST);
        mvc.perform(json(put("/api/v1/me/external-blogs/11/posts/31/topic"), "{\"topicId\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.topicId").value(4))
                .andExpect(jsonPath("$.result.topicSource").value("OWNER"));

        when(topicService.changePostTopic(USER, 11L, 32L, 4L)).thenThrow(
                new BusinessException(ErrorCode.EXTERNAL_POST_NOT_FOUND, "x"));
        mvc.perform(json(put("/api/v1/me/external-blogs/11/posts/32/topic"), "{\"topicId\":4}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_POST_NOT_FOUND"));

        when(topicService.changePostTopic(USER, 13L, 31L, 4L)).thenThrow(
                BusinessException.withParams(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT, "x",
                        java.util.Map.of("status", "RELEASED", "action", "post-topic")));
        mvc.perform(json(put("/api/v1/me/external-blogs/13/posts/31/topic"), "{\"topicId\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.params.action").value("post-topic"));

        mvc.perform(put("/api/v1/me/external-blogs/11/posts/31/topic").contentType(MediaType.APPLICATION_JSON)
                .content("{\"topicId\":4}"))
                .andExpect(status().isUnauthorized());
    }
}
