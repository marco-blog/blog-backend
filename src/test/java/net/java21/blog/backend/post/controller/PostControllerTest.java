package net.java21.blog.backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.Cookie;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.DraftResponse;
import net.java21.blog.backend.post.dto.DraftWriteRequest;
import net.java21.blog.backend.post.dto.LatestDraftResponse;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PostListFilter;
import net.java21.blog.backend.post.dto.PostLink;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.post.dto.PublishSettingsRequest;
import net.java21.blog.backend.post.dto.SavedDraftResponse;
import net.java21.blog.backend.post.service.PostDraftService;
import net.java21.blog.backend.post.service.PostPublishService;
import net.java21.blog.backend.post.service.PostService;
import net.java21.blog.backend.post.service.PostUnlockService;
import net.java21.blog.backend.post.service.ReadCompleteService;
import net.java21.blog.backend.post.service.RelatedPostService;
import net.java21.blog.backend.post.service.ViewCountService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 글 API(T065): contracts/api.md 글 절의 상태 코드·응답 형식, 401·403·404·409·422. */
@WebMvcTest(PostController.class)
@Import({WebMvcTestSupport.class, PostControllerTest.Props.class})
class PostControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PostsProperties.class)
    static class Props {
    }

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final PostDetailResponse DETAIL = new PostDetailResponse(123L, "marco", "제목", "<p>본문</p>", null,
            "본문", "/media/k3Jd9fQ2xLmA7pZ0bR5tYw", null, List.of(), PostVisibility.PUBLIC, PostStatus.PUBLISHED, 10, 2,
            true, new PostDetailResponse.Author("마르코", null), new PostLink(122L, "이전"), null, NOW, NOW, 5, null, 31L,
            false, false, null);
    private static final PostSummaryResponse SUMMARY = new PostSummaryResponse(123L, "제목", "본문", null, null,
            List.of(), 10, 2, PostVisibility.PUBLIC, PostStatus.PUBLISHED, NOW, NOW, false, false, null, null, null);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @MockitoBean
    private PostService postService;
    @MockitoBean
    private PostDraftService postDraftService;
    @MockitoBean
    private RelatedPostService relatedPostService;
    @MockitoBean
    private PostPublishService postPublishService;
    @MockitoBean
    private ViewCountService viewCountService;
    @MockitoBean
    private ReadCompleteService readCompleteService;
    @MockitoBean
    private PostUnlockService postUnlockService;

    // ---- 블로그 글 목록 ----

    @Test
    void blogPostsIsPublicPageWithTotalCount() throws Exception {
        when(postService.blogPosts(eq("marco"), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(SUMMARY), PageRequest.of(1, 20), 21));

        mvc.perform(get("/api/v1/blogs/marco/posts").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.resultCode").value("OK"))
                .andExpect(jsonPath("$.result[0].id").value(123))
                .andExpect(jsonPath("$.result[0].title").value("제목"))
                .andExpect(jsonPath("$.result[0].category").value(nullValue()))
                .andExpect(jsonPath("$.result[0].tags").isArray())
                .andExpect(jsonPath("$.result[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.result[0].publishedAt").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result[0].hasDraft").value(false))
                .andExpect(jsonPath("$.result[0].deletedAt").doesNotExist())
                .andExpect(jsonPath("$.result[0].purgeAt").doesNotExist())
                .andExpect(jsonPath("$.totalCount").value(21));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(postService).blogPosts(eq("marco"), eq(PostListFilter.NONE), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void blogPostsOfMissingBlogIs404() throws Exception {
        when(postService.blogPosts(eq("none"), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));
        expectError(mvc.perform(get("/api/v1/blogs/none/posts")), 404, "BLOG_NOT_FOUND");
    }

    @Test
    void blogPostsPassesCategoryAndTagFilters() throws Exception {
        when(postService.blogPosts(eq("marco"), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/blogs/marco/posts").param("category", "12").param("tag", "Spring Boot"))
                .andExpect(status().isOk());

        verify(postService).blogPosts(eq("marco"), eq(new PostListFilter(12L, "Spring Boot")), any());
    }

    /** 004 T050: 월 조건(둘 다 있어야 하고 범위 안, 카테고리·태그와 함께 쓸 수 없음). */
    @Test
    void blogPostsByMonthValidatesYearAndMonth() throws Exception {
        when(postService.blogPosts(eq("marco"), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/blogs/marco/posts").param("year", "2026").param("month", "10"))
                .andExpect(status().isOk());
        verify(postService).blogPosts(eq("marco"), eq(PostListFilter.ofMonth(java.time.YearMonth.of(2026, 10))),
                any());

        expectError(mvc.perform(get("/api/v1/blogs/marco/posts").param("year", "2026")), 400, "VALIDATION_FAILED");
        mvc.perform(get("/api/v1/blogs/marco/posts").param("month", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("year"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("REQUIRED"));
        mvc.perform(get("/api/v1/blogs/marco/posts").param("year", "2026").param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("month"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("INVALID"));
        mvc.perform(get("/api/v1/blogs/marco/posts").param("year", "1969").param("month", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors.length()").value(2));
        mvc.perform(get("/api/v1/blogs/marco/posts").param("year", "2026").param("month", "10")
                        .param("category", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("category"));
        mvc.perform(get("/api/v1/blogs/marco/posts").param("year", "2026").param("month", "10")
                        .param("tag", "spring"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("tag"));
    }

    /** 004 T050: 공지 목록은 비로그인도 페이지로. */
    @Test
    void noticesArePublicPages() throws Exception {
        when(postService.notices(eq("marco"), any())).thenReturn(new PageImpl<>(List.of(SUMMARY),
                org.springframework.data.domain.PageRequest.of(0, 5), 7));

        mvc.perform(get("/api/v1/blogs/marco/notices").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(7))
                .andExpect(jsonPath("$.result[0].notice").value(false));
        when(postService.notices(eq("ghost"), any())).thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));
        expectError(mvc.perform(get("/api/v1/blogs/ghost/notices")), 404, "BLOG_NOT_FOUND");
    }

    @Test
    void blogPostsWithOtherBlogsCategoryIs404() throws Exception {
        when(postService.blogPosts(eq("marco"), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "x"));
        expectError(mvc.perform(get("/api/v1/blogs/marco/posts").param("category", "99")), 404,
                "CATEGORY_NOT_FOUND");
    }

    @Test
    void blogPostsRejectsNegativePage() throws Exception {
        expectError(mvc.perform(get("/api/v1/blogs/marco/posts").param("page", "-1")), 400, "VALIDATION_FAILED");
    }

    // ---- 새 임시저장 ----

    @Test
    void createDraftIs201WithIdAndSavedAt() throws Exception {
        when(postDraftService.create(eq(7L), eq("marco"), any())).thenReturn(new SavedDraftResponse(123L, NOW));

        mvc.perform(post("/api/v1/blogs/marco/posts/drafts").cookie(authCookies.user(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"contentMarkdown\":\"본문\",\"categoryId\":12,\"tags\":[\"spring\"],"
                                + "\"topicId\":31}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/posts/123/draft"))
                .andExpect(jsonPath("$.result.id").value(123))
                .andExpect(jsonPath("$.result.savedAt").value("2026-10-06T04:24:19Z"));
        ArgumentCaptor<DraftWriteRequest> request = ArgumentCaptor.forClass(DraftWriteRequest.class);
        verify(postDraftService).create(eq(7L), eq("marco"), request.capture());
        assertThat(request.getValue()).isEqualTo(new DraftWriteRequest("", "본문", 12L, List.of("spring"), 31L));
    }

    @Test
    void createDraftNeedsLogin() throws Exception {
        expectError(mvc.perform(post("/api/v1/blogs/marco/posts/drafts").contentType(MediaType.APPLICATION_JSON)
                .content("{}")), 401, "UNAUTHENTICATED");
        verifyNoInteractions(postDraftService);
    }

    @Test
    void createDraftValidatesLengths() throws Exception {
        mvc.perform(post("/api/v1/blogs/marco/posts/drafts").cookie(authCookies.user(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "가".repeat(201) + "\",\"contentMarkdown\":\""
                                + "a".repeat(200_001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors.length()").value(2));
        verify(postDraftService, never()).create(anyLong(), anyString(), any());
    }

    @Test
    void createDraftInOthersBlogIs403AndDeletedBlogIs404() throws Exception {
        when(postDraftService.create(eq(7L), eq("other"), any())).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        when(postDraftService.create(eq(7L), eq("gone"), any()))
                .thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));

        expectError(mvc.perform(post("/api/v1/blogs/other/posts/drafts").cookie(authCookies.user(7L))
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 403, "FORBIDDEN");
        expectError(mvc.perform(post("/api/v1/blogs/gone/posts/drafts").cookie(authCookies.user(7L))
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 404, "BLOG_NOT_FOUND");
    }

    // ---- 최근 임시저장 ----

    @Test
    void latestDraftReturnsItemOrNull() throws Exception {
        when(postDraftService.latest(7L, "marco")).thenReturn(new LatestDraftResponse(123L, "제목", NOW)).thenReturn(null);

        mvc.perform(get("/api/v1/blogs/marco/posts/drafts/latest").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(123))
                .andExpect(jsonPath("$.result.title").value("제목"))
                .andExpect(jsonPath("$.result.savedAt").value("2026-10-06T04:24:19Z"));
        mvc.perform(get("/api/v1/blogs/marco/posts/drafts/latest").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test
    void latestDraftNeedsLoginAlthoughUnderPublicBlogPath() throws Exception {
        expectError(mvc.perform(get("/api/v1/blogs/marco/posts/drafts/latest")), 401, "UNAUTHENTICATED");
        verifyNoInteractions(postDraftService);
    }

    // ---- 상세 ----

    @Test
    void detailIsPublicWithPostDetailShape() throws Exception {
        when(postService.detail(eq(123L), isNull(), any())).thenReturn(DETAIL);

        mvc.perform(get("/api/v1/posts/123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(123))
                .andExpect(jsonPath("$.result.blogHandle").value("marco"))
                .andExpect(jsonPath("$.result.contentHtml").value("<p>본문</p>"))
                .andExpect(jsonPath("$.result.contentMarkdown").value(nullValue()))
                .andExpect(jsonPath("$.result.thumbnailUrl").value("/media/k3Jd9fQ2xLmA7pZ0bR5tYw"))
                .andExpect(jsonPath("$.result.category").value(nullValue()))
                .andExpect(jsonPath("$.result.tags").isArray())
                .andExpect(jsonPath("$.result.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.result.viewCount").value(10))
                .andExpect(jsonPath("$.result.commentCount").value(2))
                .andExpect(jsonPath("$.result.commentEnabled").value(true))
                .andExpect(jsonPath("$.result.author.nickname").value("마르코"))
                .andExpect(jsonPath("$.result.author.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.prev.id").value(122))
                .andExpect(jsonPath("$.result.prev.title").value("이전"))
                .andExpect(jsonPath("$.result.next").value(nullValue()))
                .andExpect(jsonPath("$.result.publishedAt").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result.updatedAt").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result.likeCount").value(5))
                .andExpect(jsonPath("$.result.likedByMe").value(nullValue()))
                .andExpect(header().string("Cache-Control", "private, no-cache"));
    }

    @Test
    void detailPassesLoggedInViewer() throws Exception {
        when(postService.detail(eq(123L), eq(7L), any())).thenReturn(new PostDetailResponse(123L, "marco", "제목",
                "<p>본문</p>",
                null, "본문", null, null, List.of(), PostVisibility.PUBLIC, PostStatus.PUBLISHED, 10, 2, true,
                new PostDetailResponse.Author("마르코", null), null, null, NOW, NOW, 5, true, null, false, false, null));
        mvc.perform(get("/api/v1/posts/123").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.likedByMe").value(true))
                .andExpect(header().string("Cache-Control", "private, no-cache"));
        verify(postService).detail(eq(123L), eq(7L), any());
    }

    @Test
    void privatePostForAnonymousIs404() throws Exception {
        when(postService.detail(eq(123L), isNull(), any()))
                .thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "x"));
        expectError(mvc.perform(get("/api/v1/posts/123")), 404, "POST_NOT_FOUND");
    }

    // ---- 작성 중 사본 ----

    @Test
    void getDraftReturnsDraftWriteWithSavedAt() throws Exception {
        when(postDraftService.get(7L, 123L))
                .thenReturn(new DraftResponse("제목", "본문", 12L, List.of("spring"), 31L, NOW));

        mvc.perform(get("/api/v1/posts/123/draft").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.title").value("제목"))
                .andExpect(jsonPath("$.result.contentMarkdown").value("본문"))
                .andExpect(jsonPath("$.result.categoryId").value(12))
                .andExpect(jsonPath("$.result.tags[0]").value("spring"))
                .andExpect(jsonPath("$.result.topicId").value(31))
                .andExpect(jsonPath("$.result.savedAt").value("2026-10-06T04:24:19Z"));
    }

    @Test
    void getDraftNeedsLoginAndOwnership() throws Exception {
        expectError(mvc.perform(get("/api/v1/posts/123/draft")), 401, "UNAUTHENTICATED");
        when(postDraftService.get(8L, 123L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        expectError(mvc.perform(get("/api/v1/posts/123/draft").cookie(authCookies.user(8L))), 403, "FORBIDDEN");
    }

    @Test
    void saveDraftReturnsIdAndSavedAt() throws Exception {
        when(postDraftService.save(eq(7L), eq(123L), any())).thenReturn(new SavedDraftResponse(123L, NOW));

        mvc.perform(put("/api/v1/posts/123/draft").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제목\",\"contentMarkdown\":\"본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(123))
                .andExpect(jsonPath("$.result.savedAt").value("2026-10-06T04:24:19Z"));
    }

    @Test
    void saveDraftOfOthersPostIs403() throws Exception {
        when(postDraftService.save(eq(8L), eq(123L), any())).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        expectError(mvc.perform(put("/api/v1/posts/123/draft").cookie(authCookies.user(8L))
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 403, "FORBIDDEN");
    }

    @Test
    void discardDraftIs200NullOr409ForUnpublished() throws Exception {
        mvc.perform(delete("/api/v1/posts/123/draft").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(postDraftService).discard(7L, 123L);

        doThrow(new BusinessException(ErrorCode.POST_NOT_PUBLISHED, "x")).when(postDraftService).discard(7L, 124L);
        expectError(mvc.perform(delete("/api/v1/posts/124/draft").cookie(authCookies.user(7L))), 409,
                "POST_NOT_PUBLISHED");
    }

    // ---- 발행 ----

    @Test
    void publishReturnsPostDetail() throws Exception {
        when(postPublishService.publish(eq(7L), eq(123L), any())).thenReturn(DETAIL);

        mvc.perform(post("/api/v1/posts/123/publish").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PUBLIC\",\"thumbnailMediaKey\":\"k3Jd9fQ2xLmA7pZ0bR5tYw\","
                                + "\"commentEnabled\":true,\"categoryId\":12,\"tags\":[\"spring\"],\"topicId\":31}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(123))
                .andExpect(jsonPath("$.result.topicId").value(31))
                .andExpect(jsonPath("$.result.status").value("PUBLISHED"));
        ArgumentCaptor<PublishSettingsRequest> request = ArgumentCaptor.forClass(PublishSettingsRequest.class);
        verify(postPublishService).publish(eq(7L), eq(123L), request.capture());
        assertThat(request.getValue().visibility()).isEqualTo(PostVisibility.PUBLIC);
        assertThat(request.getValue().topicId()).isEqualTo(31L);
    }

    @Test
    void publishValidatesSettings() throws Exception {
        mvc.perform(post("/api/v1/posts/123/publish").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thumbnailMediaKey\":\"../etc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors.length()").value(2));
        verify(postPublishService, never()).publish(anyLong(), anyLong(), any());
    }

    @Test
    void publishErrors() throws Exception {
        when(postPublishService.publish(eq(7L), eq(1L), any()))
                .thenThrow(new BusinessException(ErrorCode.POST_CONTENT_EMPTY, "x"));
        when(postPublishService.publish(eq(7L), eq(2L), any())).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        String body = "{\"visibility\":\"PRIVATE\"}";

        expectError(mvc.perform(post("/api/v1/posts/1/publish").cookie(authCookies.user(7L))
                .contentType(MediaType.APPLICATION_JSON).content(body)), 422, "POST_CONTENT_EMPTY");
        expectError(mvc.perform(post("/api/v1/posts/2/publish").cookie(authCookies.user(7L))
                .contentType(MediaType.APPLICATION_JSON).content(body)), 403, "FORBIDDEN");
        expectError(mvc.perform(post("/api/v1/posts/2/publish").contentType(MediaType.APPLICATION_JSON).content(body)),
                401, "UNAUTHENTICATED");
    }

    // ---- 005 US3 T092: 트랙백 주소·수·보내기 ----

    @Test
    void detailCarriesTrackbackUrlAndCount() throws Exception {
        PostDetailResponse withTrackbacks = new PostDetailResponse(123L, "marco", "제목", "<p>본문</p>", null, "본문",
                null, null, List.of(), PostVisibility.PUBLIC, PostStatus.PUBLISHED, 10, 2, true,
                new PostDetailResponse.Author("마르코", null), null, null, NOW, NOW, 5, null, null, false, false, null,
                false, "https://blog.java21.net/marco/123/trackback", 4);
        when(postService.detail(eq(123L), eq(null), any())).thenReturn(withTrackbacks);

        mvc.perform(get("/api/v1/posts/123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.trackbackUrl").value("https://blog.java21.net/marco/123/trackback"))
                .andExpect(jsonPath("$.result.trackbackCount").value(4));
        when(postService.detail(eq(124L), eq(null), any())).thenReturn(DETAIL);
        mvc.perform(get("/api/v1/posts/124"))
                .andExpect(jsonPath("$.result.trackbackUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.trackbackCount").value(0));
    }

    @Test
    void publishPassesTrackbackUrlsAndReportsTheirErrors() throws Exception {
        when(postPublishService.publish(eq(7L), eq(123L), any())).thenReturn(DETAIL);
        mvc.perform(post("/api/v1/posts/123/publish").cookie(authCookies.user(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PUBLIC\",\"trackbackUrls\":[\"https://a.example/tb\"]}"))
                .andExpect(status().isOk());
        ArgumentCaptor<PublishSettingsRequest> request = ArgumentCaptor.forClass(PublishSettingsRequest.class);
        verify(postPublishService).publish(eq(7L), eq(123L), request.capture());
        assertThat(request.getValue().trackbackUrls()).containsExactly("https://a.example/tb");

        when(postPublishService.publish(eq(7L), eq(5L), any())).thenThrow(new BusinessException(
                ErrorCode.VALIDATION_FAILED, "x", List.of(net.java21.blog.backend.common.api.FieldError.of(
                        "trackbackUrls[0]", "INVALID"))));
        when(postPublishService.publish(eq(7L), eq(6L), any()))
                .thenThrow(new BusinessException(ErrorCode.TRACKBACK_NOT_ALLOWED, "x"));
        String body = "{\"visibility\":\"PRIVATE\",\"trackbackUrls\":[\"x\"]}";
        mvc.perform(post("/api/v1/posts/5/publish").cookie(authCookies.user(7L))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("trackbackUrls[0]"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("INVALID"));
        expectError(mvc.perform(post("/api/v1/posts/6/publish").cookie(authCookies.user(7L))
                .contentType(MediaType.APPLICATION_JSON).content(body)), 422, "TRACKBACK_NOT_ALLOWED");
    }

    // ---- 휴지통 ----

    @Test
    void deleteIs200Null() throws Exception {
        mvc.perform(delete("/api/v1/posts/123").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(postService).delete(7L, 123L);
    }

    @Test
    void deleteNeedsLoginAndOwnership() throws Exception {
        expectError(mvc.perform(delete("/api/v1/posts/123")), 401, "UNAUTHENTICATED");
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "x")).when(postService).delete(8L, 123L);
        expectError(mvc.perform(delete("/api/v1/posts/123").cookie(authCookies.user(8L))), 403, "FORBIDDEN");
    }

    @Test
    void restoreReturnsSummaryOr422() throws Exception {
        when(postService.restore(7L, 123L)).thenReturn(SUMMARY);
        mvc.perform(post("/api/v1/posts/123/restore").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(123))
                .andExpect(jsonPath("$.result.status").value("PUBLISHED"));

        when(postService.restore(7L, 124L)).thenThrow(new BusinessException(ErrorCode.POST_NOT_IN_TRASH, "x"));
        expectError(mvc.perform(post("/api/v1/posts/124/restore").cookie(authCookies.user(7L))), 422,
                "POST_NOT_IN_TRASH");
    }

    // ---- 보호 글 열기·예약 취소(004 US3) ----

    @Test
    void unlockReturnsDetailWithCookieAndNoStore() throws Exception {
        ResponseCookie cookie = ResponseCookie.from("post_unlock_123", "token").httpOnly(true).secure(true)
                .sameSite("Lax").path("/").maxAge(1800).build();
        when(postUnlockService.unlock(eq(123L), isNull(), eq("1234"), isNull(), anyString()))
                .thenReturn(new PostUnlockService.Unlocked(DETAIL, cookie));

        mvc.perform(post("/api/v1/posts/123/unlock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(123))
                .andExpect(jsonPath("$.result.locked").value(false))
                .andExpect(jsonPath("$.result.contentHtml").value("<p>본문</p>"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", containsString("post_unlock_123=token")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=1800")));
    }

    @Test
    void unlockUsesExistingVisitorCookieAndMemberId() throws Exception {
        ResponseCookie cookie = ResponseCookie.from("post_unlock_123", "t").build();
        when(postUnlockService.unlock(eq(123L), eq(7L), eq("1234"), eq("u:7"), anyString()))
                .thenReturn(new PostUnlockService.Unlocked(DETAIL, cookie));
        mvc.perform(post("/api/v1/posts/123/unlock").cookie(authCookies.user(7L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk());

        when(postUnlockService.unlock(eq(123L), isNull(), eq("1234"),
                eq("v:3f1c2b8e-1111-2222-3333-444455556666"), anyString()))
                .thenReturn(new PostUnlockService.Unlocked(DETAIL, cookie));
        MvcResult result = mvc.perform(post("/api/v1/posts/123/unlock")
                        .cookie(new Cookie("visitor_id", "3f1c2b8e-1111-2222-3333-444455556666"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("visitor_id"));
    }

    @Test
    void unlockErrors() throws Exception {
        when(postUnlockService.unlock(eq(123L), isNull(), eq("wrong"), isNull(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.POST_PASSWORD_MISMATCH, "x"));
        expectError(mvc.perform(post("/api/v1/posts/123/unlock").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong\"}")), 400, "POST_PASSWORD_MISMATCH");

        when(postUnlockService.unlock(eq(124L), isNull(), any(), isNull(), anyString()))
                .thenThrow(BusinessException.retryAfter(ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED, "x", 600));
        expectError(mvc.perform(post("/api/v1/posts/124/unlock").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"x\"}")), 429, "PASSWORD_ATTEMPTS_EXCEEDED")
                .andExpect(header().string("Retry-After", "600"));

        when(postUnlockService.unlock(eq(125L), isNull(), isNull(), isNull(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "x"));
        expectError(mvc.perform(post("/api/v1/posts/125/unlock")), 404, "POST_NOT_FOUND");
    }

    @Test
    void unlockFromForeignOriginIsRejected() throws Exception {
        mvc.perform(post("/api/v1/posts/123/unlock").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"1234\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(postUnlockService);
    }

    @Test
    void lockedDetailShowsLockedFlag() throws Exception {
        PostDetailResponse locked = new PostDetailResponse(123L, "marco", "제목", null, null, null, null, null,
                List.of(), PostVisibility.PROTECTED, PostStatus.PUBLISHED, 10, 2, true,
                new PostDetailResponse.Author("마르코", null), null, null, NOW, NOW, 5, null, null, false, true, null);
        when(postService.detail(eq(123L), isNull(), any())).thenReturn(locked);
        mvc.perform(get("/api/v1/posts/123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.locked").value(true))
                .andExpect(jsonPath("$.result.visibility").value("PROTECTED"))
                .andExpect(jsonPath("$.result.contentHtml").value(nullValue()))
                .andExpect(jsonPath("$.result.scheduledAt").value(nullValue()));
    }

    @Test
    void unscheduleReturnsDraftSummary() throws Exception {
        PostSummaryResponse draft = new PostSummaryResponse(123L, "제목", "본문", null, null, List.of(), 0, 0,
                PostVisibility.PUBLIC, PostStatus.DRAFT, null, NOW, false, false, null, null, null);
        when(postService.unschedule(7L, 123L)).thenReturn(draft);
        mvc.perform(post("/api/v1/posts/123/unschedule").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("DRAFT"))
                .andExpect(jsonPath("$.result.scheduledAt").value(nullValue()));
    }

    @Test
    void unscheduleErrors() throws Exception {
        expectError(mvc.perform(post("/api/v1/posts/123/unschedule")), 401, "UNAUTHENTICATED");
        when(postService.unschedule(7L, 124L)).thenThrow(new BusinessException(ErrorCode.POST_NOT_SCHEDULED, "x"));
        expectError(mvc.perform(post("/api/v1/posts/124/unschedule").cookie(authCookies.user(7L))), 409,
                "POST_NOT_SCHEDULED");
        when(postService.unschedule(8L, 123L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        expectError(mvc.perform(post("/api/v1/posts/123/unschedule").cookie(authCookies.user(8L))), 403,
                "FORBIDDEN");
    }

    // ---- 조회수 ----

    @Test
    void viewWithVisitorCookieIs200NullAndIssuesNoCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/posts/123/views")
                        .cookie(new Cookie("visitor_id", "3f1c2b8e-1111-2222-3333-444455556666")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andReturn();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        verify(viewCountService).record(eq(123L), isNull(), eq("v:3f1c2b8e-1111-2222-3333-444455556666"), any());
    }

    @Test
    void viewWithoutVisitorCookieIssuesOne() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/posts/123/views").cookie(new Cookie("visitor_id", "<bad>")))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("visitor_id=")))
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("Path=/", "HttpOnly", "Secure", "SameSite=Lax", "Max-Age=31536000");
        String visitorId = setCookie.substring("visitor_id=".length(), setCookie.indexOf(';'));
        verify(viewCountService).record(eq(123L), isNull(), eq("v:" + visitorId), any());
    }

    @Test
    void viewOfLoggedInMemberUsesMemberId() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/posts/123/views").cookie(authCookies.user(7L)))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        verify(viewCountService).record(eq(123L), eq(7L), eq("u:7"), any());
    }

    @Test
    void viewOfInvisiblePostIs404() throws Exception {
        when(viewCountService.record(eq(123L), isNull(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "x"));
        expectError(mvc.perform(post("/api/v1/posts/123/views")), 404, "POST_NOT_FOUND");
    }

    @Test
    void viewFromForeignOriginIsRejected() throws Exception {
        expectError(mvc.perform(post("/api/v1/posts/123/views").header("Origin", "https://evil.example.com")), 403,
                "ORIGIN_NOT_ALLOWED");
        verifyNoInteractions(viewCountService);
    }

    // ---- 끝까지 읽음(003 T030) ----

    @Test
    void readCompleteIsPublic200NullAndReusesVisitorCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/posts/123/read-complete")
                        .cookie(new Cookie("visitor_id", "3f1c2b8e-1111-2222-3333-444455556666")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andReturn();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        verify(readCompleteService).record(eq(123L), isNull(), eq("v:3f1c2b8e-1111-2222-3333-444455556666"), any());
        verifyNoInteractions(viewCountService);
    }

    @Test
    void readCompleteWithoutVisitorCookieIssuesOne() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/posts/123/read-complete"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("visitor_id=")))
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        String visitorId = setCookie.substring("visitor_id=".length(), setCookie.indexOf(';'));
        verify(readCompleteService).record(eq(123L), isNull(), eq("v:" + visitorId), any());
    }

    @Test
    void readCompleteOfLoggedInMemberUsesMemberId() throws Exception {
        mvc.perform(post("/api/v1/posts/123/read-complete").cookie(authCookies.user(7L))).andExpect(status().isOk());
        verify(readCompleteService).record(eq(123L), eq(7L), eq("u:7"), any());
    }

    @Test
    void readCompleteOfInvisiblePostIs404() throws Exception {
        when(readCompleteService.record(eq(123L), isNull(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "x"));
        expectError(mvc.perform(post("/api/v1/posts/123/read-complete")), 404, "POST_NOT_FOUND");
    }

    @Test
    void readCompleteFromForeignOriginIsRejected() throws Exception {
        expectError(mvc.perform(post("/api/v1/posts/123/read-complete").header("Origin", "https://evil.example.com")),
                403, "ORIGIN_NOT_ALLOWED");
        verifyNoInteractions(readCompleteService);
    }

    private static ResultActions expectError(ResultActions actions, int httpStatus, String code) throws Exception {
        return actions.andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value(code))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test
    void relatedPostsAreOpenToAnonymousVisitors() throws Exception {
        when(relatedPostService.related(123L, null)).thenReturn(List.of(SUMMARY));

        mvc.perform(get("/api/v1/posts/123/related"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result[0].id").value(123))
                .andExpect(jsonPath("$.result[0].title").value("제목"))
                .andExpect(jsonPath("$.result[0].hasDraft").value(false))
                .andExpect(jsonPath("$.totalCount").doesNotExist());
    }

    @Test
    void relatedPostsPassTheViewerAndEmptyIsAnEmptyArray() throws Exception {
        when(relatedPostService.related(123L, 7L)).thenReturn(List.of());

        mvc.perform(get("/api/v1/posts/123/related").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isArray())
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void relatedPostsOfAnInvisiblePostAre404() throws Exception {
        when(relatedPostService.related(9L, null))
                .thenThrow(new BusinessException(ErrorCode.POST_NOT_FOUND, "Post not found: 9"));

        mvc.perform(get("/api/v1/posts/9/related"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("POST_NOT_FOUND"));
    }
}
