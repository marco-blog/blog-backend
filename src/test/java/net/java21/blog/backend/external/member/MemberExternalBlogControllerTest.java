package net.java21.blog.backend.external.member;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
import net.java21.blog.backend.external.member.dto.FeedPreviewResponse;
import net.java21.blog.backend.external.verify.VerificationResponse;
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

/** 007 T030: 회원 외부 블로그 API(contracts/api.md) — 로그인 필요, 201·200, 오류 코드와 params, no-store. */
@WebMvcTest(MemberExternalBlogController.class)
@Import(WebMvcTestSupport.class)
class MemberExternalBlogControllerTest {

    private static final long USER = 7L;
    private static final Instant AT = Instant.parse("2026-10-07T00:00:00Z");
    private static final String FEED = "https://remote.example/feed";
    private static final MyExternalBlogResponse BLOG = new MyExternalBlogResponse(11L, "Remote", "https://remote.example/",
            FEED, FeedFormat.RSS, ExternalBlogStatus.PENDING, RegistrationType.MEMBER_REQUEST, true, 3L, null, null,
            null, null, 0, AT);
    private static final VerificationResponse VERIFICATION = new VerificationResponse(21L, FEED,
            "java21-verify-AbCdEf123456", AT.plusSeconds(86400), null, null);

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

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.cookie(authCookies.user(USER)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void anonymousIsRejected() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(post("/api/v1/external-blog-previews"),
                post("/api/v1/me/external-blog-verifications"), post("/api/v1/me/external-blog-verifications/1/check"),
                get("/api/v1/me/external-blogs"), post("/api/v1/me/external-blogs"), get("/api/v1/me/external-blogs/1"),
                get("/api/v1/me/external-blogs/1/posts"), post("/api/v1/external-blogs/1/claim"))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(previewService, verificationService, service);
    }

    @Test
    void preview() throws Exception {
        FeedPreviewResponse preview = new FeedPreviewResponse(FEED, "https://remote.example/", "Remote", FeedFormat.RSS,
                List.of(new FeedPreviewResponse.RecentPost("P1", "https://remote.example/1", AT)),
                new FeedPreviewResponse.Registered(11L, ExternalBlogStatus.ACTIVE, true, false));
        when(previewService.preview(USER, "remote.example")).thenReturn(preview);

        mvc.perform(json(post("/api/v1/external-blog-previews"), "{\"url\":\"remote.example\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result.feedUrl").value(FEED))
                .andExpect(jsonPath("$.result.format").value("RSS"))
                .andExpect(jsonPath("$.result.recentPosts[0].title").value("P1"))
                .andExpect(jsonPath("$.result.registered.claimable").value(true));
    }

    @Test
    void previewErrorsCarryParams() throws Exception {
        when(previewService.preview(USER, "http://localhost:5173/x")).thenThrow(BusinessException.withParams(
                ErrorCode.EXTERNAL_FEED_URL_NOT_ALLOWED, "no", Map.of("reason", "SELF")));
        mvc.perform(json(post("/api/v1/external-blog-previews"), "{\"url\":\"http://localhost:5173/x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_FEED_URL_NOT_ALLOWED"))
                .andExpect(jsonPath("$.header.params.reason").value("SELF"))
                .andExpect(jsonPath("$.result").value(nullValue()));

        when(previewService.preview(USER, "")).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "x",
                List.of(FieldError.of("url", "REQUIRED"))));
        mvc.perform(json(post("/api/v1/external-blog-previews"), "{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("url"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("REQUIRED"));
    }

    @Test
    void issueReturns201ThenReuses200() throws Exception {
        when(verificationService.issue(USER, FEED)).thenReturn(new VerificationService.Issued(VERIFICATION, true),
                new VerificationService.Issued(VERIFICATION, false));

        mvc.perform(json(post("/api/v1/me/external-blog-verifications"), "{\"feedUrl\":\"" + FEED + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/me/external-blog-verifications/21"))
                .andExpect(jsonPath("$.result.code").value("java21-verify-AbCdEf123456"));
        mvc.perform(json(post("/api/v1/me/external-blog-verifications"), "{\"feedUrl\":\"" + FEED + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.result.id").value(21));
    }

    @Test
    void checkWithOrWithoutBody() throws Exception {
        VerificationResponse done = new VerificationResponse(21L, FEED, VERIFICATION.code(), VERIFICATION.expiresAt(),
                AT, 11L);
        when(verificationService.check(USER, 21L, null)).thenReturn(done);
        when(verificationService.check(USER, 21L, FEED)).thenReturn(done);

        mvc.perform(post("/api/v1/me/external-blog-verifications/21/check").cookie(authCookies.user(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.verifiedAt").value("2026-10-07T00:00:00Z"))
                .andExpect(jsonPath("$.result.claimableExternalBlogId").value(11));
        mvc.perform(json(post("/api/v1/me/external-blog-verifications/21/check"), "{\"feedUrl\":\"" + FEED + "\"}"))
                .andExpect(status().isOk());

        when(verificationService.check(eq(USER), eq(22L), isNull())).thenThrow(BusinessException.withParams(
                ErrorCode.EXTERNAL_VERIFICATION_CODE_NOT_FOUND, "x",
                Map.of("checked", List.of("FEED", "SITE"), "failures", Map.of("SITE", "TIMEOUT"))));
        mvc.perform(post("/api/v1/me/external-blog-verifications/22/check").cookie(authCookies.user(USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_VERIFICATION_CODE_NOT_FOUND"))
                .andExpect(jsonPath("$.header.params.checked[1]").value("SITE"))
                .andExpect(jsonPath("$.header.params.failures.SITE").value("TIMEOUT"));
    }

    @Test
    void createListGetPosts() throws Exception {
        when(service.create(USER, FEED, 3L, 21L)).thenReturn(BLOG);
        mvc.perform(json(post("/api/v1/me/external-blogs"),
                "{\"feedUrl\":\"" + FEED + "\",\"defaultTopicId\":3,\"verificationId\":21}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/me/external-blogs/11"))
                .andExpect(jsonPath("$.result.status").value("PENDING"))
                .andExpect(jsonPath("$.result.ownershipVerified").value(true));

        when(service.list(USER)).thenReturn(List.of(BLOG));
        mvc.perform(get("/api/v1/me/external-blogs").cookie(authCookies.user(USER)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result[0].registrationType").value("MEMBER_REQUEST"));

        when(service.get(USER, 11L)).thenReturn(BLOG);
        mvc.perform(get("/api/v1/me/external-blogs/11").cookie(authCookies.user(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.feedUrl").value(FEED));

        MyExternalPostResponse post = new MyExternalPostResponse(31L, "P1", "s", "https://remote.example/1",
                "/media/external/abc", AT, 3L, TopicSource.DEFAULT, ExternalPostStatus.ACTIVE, null, 4);
        when(service.posts(eq(USER), eq(11L), any())).thenReturn(new PageImpl<>(List.of(post), PageRequest.of(0, 20),
                1));
        mvc.perform(get("/api/v1/me/external-blogs/11/posts?page=1&size=20").cookie(authCookies.user(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.result[0].thumbnailUrl").value("/media/external/abc"))
                .andExpect(jsonPath("$.result[0].clickCount").value(4));
    }

    @Test
    void createErrors() throws Exception {
        when(service.create(USER, FEED, 3L, null)).thenThrow(BusinessException.withParams(
                ErrorCode.EXTERNAL_BLOG_ALREADY_REGISTERED, "dup",
                Map.of("externalBlogId", 9, "status", "ACTIVE", "claimable", true, "mine", false)));
        mvc.perform(json(post("/api/v1/me/external-blogs"), "{\"feedUrl\":\"" + FEED + "\",\"defaultTopicId\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_BLOG_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.header.params.externalBlogId").value(9))
                .andExpect(jsonPath("$.header.params.claimable").value(true));

        when(service.create(USER, FEED, null, null)).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED,
                "x", List.of(FieldError.of("defaultTopicId", "REQUIRED"))));
        mvc.perform(json(post("/api/v1/me/external-blogs"), "{\"feedUrl\":\"" + FEED + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("defaultTopicId"));

        when(service.get(USER, 99L)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_BLOG_NOT_FOUND, "x"));
        mvc.perform(get("/api/v1/me/external-blogs/99").cookie(authCookies.user(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_BLOG_NOT_FOUND"));

        when(service.create(USER, "https://x.example/feed", 3L, null)).thenThrow(BusinessException.withParams(
                ErrorCode.EXTERNAL_BLOG_LIMIT_EXCEEDED, "x", Map.of("max", 3)));
        mvc.perform(json(post("/api/v1/me/external-blogs"),
                "{\"feedUrl\":\"https://x.example/feed\",\"defaultTopicId\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.params.max").value(3));
    }

    @Test
    void claim() throws Exception {
        when(service.claim(USER, 11L, 21L)).thenReturn(BLOG);
        mvc.perform(json(post("/api/v1/external-blogs/11/claim"), "{\"verificationId\":21}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(11));

        when(service.claim(USER, 12L, 21L)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT,
                "x"));
        mvc.perform(json(post("/api/v1/external-blogs/12/claim"), "{\"verificationId\":21}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_BLOG_STATE_CONFLICT"));
    }
}
