package net.java21.blog.backend.blog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import net.java21.blog.backend.blog.dto.BlogResponse;
import net.java21.blog.backend.blog.dto.CreateBlogRequest;
import net.java21.blog.backend.blog.dto.MyBlogsResponse;
import net.java21.blog.backend.blog.dto.UpdateBlogRequest;
import net.java21.blog.backend.blog.service.BlogService;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 블로그 API(T058): 응답 틀과 401·403·404·409·422. */
@WebMvcTest(BlogController.class)
@Import(WebMvcTestSupport.class)
class BlogControllerTest {

    private static final BlogResponse MARCO = new BlogResponse("marco", "마르코의 블로그", null, null, true,
            new BlogResponse.Owner("마르코", null, "자바 개발자"), List.of(), 3, null, 20,
            net.java21.blog.backend.blog.domain.FeedContentMode.FULL, true, null, true, false);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @MockitoBean
    private BlogService blogService;

    @Test
    void myBlogsReturnsItemsCountAndLimit() throws Exception {
        when(blogService.myBlogs(7L)).thenReturn(new MyBlogsResponse(List.of(
                new MyBlogsResponse.Item("marco", "첫째", null, 3, Instant.parse("2026-10-06T04:24:19Z")),
                new MyBlogsResponse.Item("marco-dev", "둘째", null, 0, Instant.parse("2026-10-07T00:00:00Z"))),
                2, 3));

        mvc.perform(get("/api/v1/me/blogs").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.resultCode").value("OK"))
                .andExpect(jsonPath("$.result.items.length()").value(2))
                .andExpect(jsonPath("$.result.items[0].handle").value("marco"))
                .andExpect(jsonPath("$.result.items[0].postCount").value(3))
                .andExpect(jsonPath("$.result.items[0].coverImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.items[0].createdAt").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result.count").value(2))
                .andExpect(jsonPath("$.result.limit").value(3))
                .andExpect(jsonPath("$.totalCount").doesNotExist());
    }

    @Test
    void myBlogsNeedsLogin() throws Exception {
        expectError(mvc.perform(get("/api/v1/me/blogs")), 401, "UNAUTHENTICATED");
    }

    @Test
    void createIs201WithLocation() throws Exception {
        when(blogService.create(eq(7L), any(CreateBlogRequest.class))).thenReturn(MARCO);

        mvc.perform(post("/api/v1/blogs").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handle\":\"marco\",\"title\":\"마르코의 블로그\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/blogs/marco"))
                .andExpect(jsonPath("$.result.handle").value("marco"))
                .andExpect(jsonPath("$.result.owner.nickname").value("마르코"));
    }

    @Test
    void createValidation() throws Exception {
        mvc.perform(post("/api/v1/blogs").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handle\":\"\",\"title\":\"" + "가".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors.length()").value(2));
        verify(blogService, never()).create(anyLong(), any());
    }

    @Test
    void createNeedsLogin() throws Exception {
        expectError(mvc.perform(post("/api/v1/blogs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"marco\"}")), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @CsvSource({"BLOG_LIMIT_EXCEEDED, 409", "HANDLE_TAKEN, 409", "HANDLE_RESERVED, 422", "HANDLE_INVALID, 422"})
    void createErrors(ErrorCode code, int httpStatus) throws Exception {
        when(blogService.create(eq(7L), any())).thenThrow(new BusinessException(code, "x"));
        expectError(mvc.perform(post("/api/v1/blogs").cookie(authCookies.user(7L))
                .contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"marco-x\"}")), httpStatus, code.name());
    }

    @Test
    void getIsPublicWithOwnerAndEmptyCategories() throws Exception {
        when(blogService.get("marco", null)).thenReturn(MARCO);

        mvc.perform(get("/api/v1/blogs/marco"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.handle").value("marco"))
                .andExpect(jsonPath("$.result.title").value("마르코의 블로그"))
                .andExpect(jsonPath("$.result.description").value(nullValue()))
                .andExpect(jsonPath("$.result.coverImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.commentEnabled").value(true))
                .andExpect(jsonPath("$.result.owner.nickname").value("마르코"))
                .andExpect(jsonPath("$.result.owner.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.owner.bio").value("자바 개발자"))
                .andExpect(jsonPath("$.result.categories").isArray())
                .andExpect(jsonPath("$.result.categories.length()").value(0))
                .andExpect(jsonPath("$.result.subscriberCount").value(3))
                .andExpect(jsonPath("$.result.subscribedByMe").value(nullValue()))
                .andExpect(jsonPath("$.result.feedItemCount").value(20))
                .andExpect(jsonPath("$.result.feedContentMode").value("FULL"))
                .andExpect(jsonPath("$.result.portalEnabled").value(true))
                .andExpect(jsonPath("$.result.defaultTopicId").value(nullValue()))
                .andExpect(jsonPath("$.result.guestbookEnabled").value(true))
                .andExpect(jsonPath("$.result.guestWriteEnabled").value(false))
                .andExpect(header().string("Cache-Control", "private, no-cache"));
    }

    /** 002 T023: 로그인했으면 그 회원으로 구독 여부를 묻는다. 보는 사람마다 다르므로 private, no-cache. */
    @Test
    void getPassesViewerForSubscribedByMe() throws Exception {
        when(blogService.get("marco", 7L)).thenReturn(new BlogResponse("marco", "마르코의 블로그", null, null, true,
                new BlogResponse.Owner("마르코", null, null), List.of(), 3, true, 20,
                net.java21.blog.backend.blog.domain.FeedContentMode.FULL, true, null, true, false));

        mvc.perform(get("/api/v1/blogs/marco").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.subscribedByMe").value(true))
                .andExpect(header().string("Cache-Control", "private, no-cache"));
    }

    @Test
    void getUnknownBlogIs404() throws Exception {
        when(blogService.get("gone", null)).thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));
        expectError(mvc.perform(get("/api/v1/blogs/gone")), 404, "BLOG_NOT_FOUND");
    }

    @Test
    void patchPassesOnlySentFields() throws Exception {
        when(blogService.update(eq(7L), eq("marco"), any(UpdateBlogRequest.class))).thenReturn(MARCO);

        mvc.perform(patch("/api/v1/blogs/marco").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null,\"commentEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.handle").value("marco"));

        ArgumentCaptor<UpdateBlogRequest> request = ArgumentCaptor.forClass(UpdateBlogRequest.class);
        verify(blogService).update(eq(7L), eq("marco"), request.capture());
        assertThat(request.getValue().hasTitle()).isFalse();
        assertThat(request.getValue().hasDescription()).isTrue();
        assertThat(request.getValue().getDescription()).isNull();
        assertThat(request.getValue().hasCommentEnabled()).isTrue();
        assertThat(request.getValue().getCommentEnabled()).isFalse();
    }

    @Test
    void patchValidatesLengths() throws Exception {
        mvc.perform(patch("/api/v1/blogs/marco").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"description\":\"" + "a".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors.length()").value(2));
    }

    @ParameterizedTest
    @CsvSource({"FORBIDDEN, 403", "BLOG_NOT_FOUND, 404"})
    void patchOwnershipErrors(ErrorCode code, int httpStatus) throws Exception {
        when(blogService.update(eq(8L), eq("marco"), any())).thenThrow(new BusinessException(code, "x"));
        expectError(mvc.perform(patch("/api/v1/blogs/marco").cookie(authCookies.user(8L))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"남의 블로그\"}")), httpStatus, code.name());
    }

    @Test
    void patchPassesFeedSettings() throws Exception {
        when(blogService.update(eq(7L), eq("marco"), any(UpdateBlogRequest.class))).thenReturn(MARCO);

        mvc.perform(patch("/api/v1/blogs/marco").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedItemCount\":30,\"feedContentMode\":\"SUMMARY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.feedItemCount").exists())
                .andExpect(jsonPath("$.result.feedContentMode").exists());

        ArgumentCaptor<UpdateBlogRequest> request = ArgumentCaptor.forClass(UpdateBlogRequest.class);
        verify(blogService).update(eq(7L), eq("marco"), request.capture());
        assertThat(request.getValue().hasFeedItemCount()).isTrue();
        assertThat(request.getValue().getFeedItemCount()).isEqualTo(30);
        assertThat(request.getValue().hasFeedContentMode()).isTrue();
        assertThat(request.getValue().getFeedContentMode()).isEqualTo("SUMMARY");
        assertThat(request.getValue().hasTitle()).isFalse();
    }

    /** 003 T071: 포털 설정은 보낸 값만, null도 그대로 서비스에 넘긴다(검증은 서비스). */
    @Test
    void patchPassesPortalSettings() throws Exception {
        when(blogService.update(eq(7L), eq("marco"), any(UpdateBlogRequest.class))).thenReturn(MARCO);

        mvc.perform(patch("/api/v1/blogs/marco").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portalEnabled\":false,\"defaultTopicId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.portalEnabled").exists());

        ArgumentCaptor<UpdateBlogRequest> request = ArgumentCaptor.forClass(UpdateBlogRequest.class);
        verify(blogService).update(eq(7L), eq("marco"), request.capture());
        assertThat(request.getValue().hasPortalEnabled()).isTrue();
        assertThat(request.getValue().getPortalEnabled()).isFalse();
        assertThat(request.getValue().hasDefaultTopicId()).isTrue();
        assertThat(request.getValue().getDefaultTopicId()).isNull();
        assertThat(request.getValue().hasTitle()).isFalse();
    }

    /** 004 T011: 방명록·비회원 쓰기 설정은 보낸 값만, null도 그대로 서비스에 넘긴다(검증은 서비스). */
    @Test
    void patchPassesGuestSettings() throws Exception {
        when(blogService.update(eq(7L), eq("marco"), any(UpdateBlogRequest.class))).thenReturn(MARCO);

        mvc.perform(patch("/api/v1/blogs/marco").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestbookEnabled\":false,\"guestWriteEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.guestbookEnabled").exists());

        ArgumentCaptor<UpdateBlogRequest> request = ArgumentCaptor.forClass(UpdateBlogRequest.class);
        verify(blogService).update(eq(7L), eq("marco"), request.capture());
        assertThat(request.getValue().hasGuestbookEnabled()).isTrue();
        assertThat(request.getValue().getGuestbookEnabled()).isFalse();
        assertThat(request.getValue().hasGuestWriteEnabled()).isTrue();
        assertThat(request.getValue().getGuestWriteEnabled()).isTrue();
        assertThat(request.getValue().hasPortalEnabled()).isFalse();
    }

    @Test
    void patchTopicErrors() throws Exception {
        when(blogService.update(eq(7L), eq("marco"), any(UpdateBlogRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.TOPIC_NOT_SELECTABLE, "x"));
        expectError(mvc.perform(patch("/api/v1/blogs/marco").cookie(authCookies.user(7L))
                .contentType(MediaType.APPLICATION_JSON).content("{\"defaultTopicId\":3}")), 422, "TOPIC_NOT_SELECTABLE");
    }

    @Test
    void invalidFeedItemCountIs400WithAllowedValues() throws Exception {
        when(blogService.update(eq(7L), eq("marco"), any(UpdateBlogRequest.class))).thenThrow(new BusinessException(
                ErrorCode.VALIDATION_FAILED, "x",
                List.of(new FieldError("feedItemCount", "INVALID", Map.of("allowed", List.of(10, 20, 30, 50))))));

        mvc.perform(patch("/api/v1/blogs/marco").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedItemCount\":15}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("feedItemCount"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("INVALID"))
                .andExpect(jsonPath("$.header.fieldErrors[0].params.allowed[3]").value(50));
    }

    @Test
    void patchNeedsLogin() throws Exception {
        expectError(mvc.perform(patch("/api/v1/blogs/marco").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}")), 401, "UNAUTHENTICATED");
    }

    @Test
    void deleteWithPasswordReturnsNullResult() throws Exception {
        mvc.perform(delete("/api/v1/blogs/marco-life").cookie(authCookies.user(7L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(blogService).delete(7L, "marco-life", "password1");
    }

    @Test
    void deleteRequiresPassword() throws Exception {
        mvc.perform(delete("/api/v1/blogs/marco").cookie(authCookies.user(7L))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("REQUIRED"));
        verify(blogService, never()).delete(anyLong(), anyString(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"LAST_BLOG_CANNOT_BE_DELETED, 409", "CURRENT_PASSWORD_MISMATCH, 400", "FORBIDDEN, 403",
            "BLOG_NOT_FOUND, 404"})
    void deleteErrors(ErrorCode code, int httpStatus) throws Exception {
        doThrow(new BusinessException(code, "x")).when(blogService).delete(7L, "marco", "password1");
        expectError(mvc.perform(delete("/api/v1/blogs/marco").cookie(authCookies.user(7L))
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password1\"}")), httpStatus,
                code.name());
    }

    @Test
    void deleteNeedsLoginAndOrigin() throws Exception {
        expectError(mvc.perform(delete("/api/v1/blogs/marco").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"password1\"}")), 401, "UNAUTHENTICATED");
        expectError(mvc.perform(delete("/api/v1/blogs/marco").header("Origin", "https://evil.example")
                .cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"password1\"}")), 403, "ORIGIN_NOT_ALLOWED");
    }

    private static ResultActions expectError(ResultActions actions, int httpStatus, String code) throws Exception {
        return actions.andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value(code))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }
}
