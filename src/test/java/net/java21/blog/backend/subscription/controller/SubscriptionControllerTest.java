package net.java21.blog.backend.subscription.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.CategoryRef;
import net.java21.blog.backend.subscription.dto.FeedPostResponse;
import net.java21.blog.backend.subscription.dto.SubscriptionStateResponse;
import net.java21.blog.backend.subscription.service.SubscriptionService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 구독·구독 피드 API(T022): PUT·DELETE 상태 응답, {@code GET /me/feed} Page&lt;FeedPost&gt;, 401·404·422, {@code no-store}. */
@WebMvcTest(SubscriptionController.class)
@Import(WebMvcTestSupport.class)
class SubscriptionControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @MockitoBean
    private SubscriptionService service;

    @Test
    void putSubscribes() throws Exception {
        when(service.subscribe(7L, "marco")).thenReturn(new SubscriptionStateResponse("marco", true, 3));

        mvc.perform(put("/api/v1/me/subscriptions/marco").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.resultCode").value("OK"))
                .andExpect(jsonPath("$.result.handle").value("marco"))
                .andExpect(jsonPath("$.result.subscribed").value(true))
                .andExpect(jsonPath("$.result.subscriberCount").value(3))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void deleteUnsubscribes() throws Exception {
        when(service.unsubscribe(7L, "marco")).thenReturn(new SubscriptionStateResponse("marco", false, 2));

        mvc.perform(delete("/api/v1/me/subscriptions/marco").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.subscribed").value(false))
                .andExpect(jsonPath("$.result.subscriberCount").value(2));
    }

    @Test
    void ownBlogIs422AndUnknownIs404() throws Exception {
        when(service.subscribe(7L, "mine")).thenThrow(new BusinessException(ErrorCode.CANNOT_SUBSCRIBE_OWN_BLOG, "x"));
        when(service.subscribe(7L, "nope")).thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));

        mvc.perform(put("/api/v1/me/subscriptions/mine").cookie(authCookies.user(7L)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("CANNOT_SUBSCRIBE_OWN_BLOG"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(put("/api/v1/me/subscriptions/nope").cookie(authCookies.user(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BLOG_NOT_FOUND"));
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(put("/api/v1/me/subscriptions/marco")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        mvc.perform(delete("/api/v1/me/subscriptions/marco")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/feed")).andExpect(status().isUnauthorized());
        verify(service, never()).subscribe(anyLong(), anyString());
    }

    @Test
    void feedReturnsPageOfFeedPosts() throws Exception {
        FeedPostResponse item = new FeedPostResponse(5L, "글", "요약", null, new CategoryRef(3L, "Spring"),
                List.of("java"), 10, 2, PostVisibility.PUBLIC, PostStatus.PUBLISHED, NOW, NOW, false,
                new FeedPostResponse.BlogRef("marco", "마르코의 블로그"), new FeedPostResponse.Author("마르코", null));
        when(service.feed(eq(7L), any(Pageable.class))).thenAnswer(inv -> new PageImpl<>(List.of(item),
                inv.getArgument(1, Pageable.class), 21));

        mvc.perform(get("/api/v1/me/feed?page=1&size=20").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(21))
                .andExpect(jsonPath("$.result[0].id").value(5))
                .andExpect(jsonPath("$.result[0].title").value("글"))
                .andExpect(jsonPath("$.result[0].summary").value("요약"))
                .andExpect(jsonPath("$.result[0].category.name").value("Spring"))
                .andExpect(jsonPath("$.result[0].tags[0]").value("java"))
                .andExpect(jsonPath("$.result[0].hasDraft").value(false))
                .andExpect(jsonPath("$.result[0].publishedAt").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result[0].blog.handle").value("marco"))
                .andExpect(jsonPath("$.result[0].blog.title").value("마르코의 블로그"))
                .andExpect(jsonPath("$.result[0].author.nickname").value("마르코"))
                .andExpect(jsonPath("$.result[0].author.profileImageUrl").value(nullValue()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).feed(eq(7L), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
    }

    @Test
    void feedPageValidation() throws Exception {
        mvc.perform(get("/api/v1/me/feed?page=-1").cookie(authCookies.user(7L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"));
    }
}
