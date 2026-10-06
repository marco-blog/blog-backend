package net.java21.blog.backend.syndication.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.WebMvcTestSupport;
import net.java21.blog.backend.syndication.service.FeedPlan;
import net.java21.blog.backend.syndication.service.FeedService;
import net.java21.blog.backend.syndication.service.FeedSnapshot;
import net.java21.blog.backend.syndication.writer.AtomFeedWriter;
import net.java21.blog.backend.syndication.writer.RssFeedWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 블로그 피드 경로(T086, FR-044·045·048): {@code /{handle}/rss}·{@code /{handle}/atom}·{@code /{handle}/category/{id}/rss} 비로그인 200과
 * {@code Content-Type}, {@code ETag}·{@code Last-Modified}·{@code Cache-Control: no-cache}, 같은 {@code If-None-Match}면 304 본문 없음
 * (본문 조회 없음), 404는 공통 틀 JSON(리더가 XML만 받겠다고 해도).
 */
@WebMvcTest(FeedController.class)
@Import({WebMvcTestSupport.class, RssFeedWriter.class, AtomFeedWriter.class})
class FeedControllerTest {

    private static final Instant MODIFIED = Instant.parse("2026-10-06T04:24:19Z");
    private static final String ETAG = "W/\"abc123\"";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FeedService feedService;

    @Test
    void rssFeed() throws Exception {
        givenFeed(null);

        MvcResult result = mvc.perform(get("/marco/rss"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/rss+xml;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().string(HttpHeaders.LAST_MODIFIED, "Tue, 06 Oct 2026 04:24:19 GMT"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andReturn();

        String xml = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(xml).contains("<rss", "<title>마르코의 블로그</title>", "https://blog.java21.net/marco/rss");
    }

    @Test
    void atomFeed() throws Exception {
        givenFeed(null);

        MvcResult result = mvc.perform(get("/marco/atom"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/atom+xml;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("<feed xmlns=\"http://www.w3.org/2005/Atom\"", "https://blog.java21.net/marco/atom");
    }

    @Test
    void categoryRssFeed() throws Exception {
        givenFeed(7L);

        mvc.perform(get("/marco/category/7/rss"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/rss+xml;charset=UTF-8"));
        verify(feedService).plan("marco", 7L);
    }

    @Test
    void matchingIfNoneMatchIs304WithoutBody() throws Exception {
        FeedPlan plan = new FeedPlan(null, List.of(), ETAG, MODIFIED);
        when(feedService.plan("marco", null)).thenReturn(plan);

        mvc.perform(get("/marco/rss").header(HttpHeaders.IF_NONE_MATCH, ETAG))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(content().bytes(new byte[0]));
        mvc.perform(get("/marco/atom").header(HttpHeaders.IF_MODIFIED_SINCE, "Tue, 06 Oct 2026 04:24:19 GMT"))
                .andExpect(status().isNotModified());
        verify(feedService, never()).snapshot(any());
    }

    @Test
    void staleIfNoneMatchGetsTheFeed() throws Exception {
        givenFeed(null);

        mvc.perform(get("/marco/rss").header(HttpHeaders.IF_NONE_MATCH, "W/\"old\""))
                .andExpect(status().isOk());
    }

    @Test
    void missingBlogIs404InTheCommonEnvelope() throws Exception {
        when(feedService.plan("nobody", null))
                .thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "Blog not found: nobody"));
        when(feedService.plan("marco", 99L))
                .thenThrow(new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found: 99"));

        mvc.perform(get("/nobody/rss").header(HttpHeaders.ACCEPT, "application/rss+xml"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BLOG_NOT_FOUND"));
        mvc.perform(get("/nobody/atom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.isSuccessful").value(false));
        mvc.perform(get("/marco/category/99/rss"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void nonNumericCategoryIs404() throws Exception {
        mvc.perform(get("/marco/category/abc/rss")).andExpect(status().isNotFound());
        verify(feedService, never()).plan(any(), any());
    }

    private void givenFeed(Long categoryId) {
        FeedPlan plan = new FeedPlan(null, List.of(), ETAG, MODIFIED);
        when(feedService.plan("marco", categoryId)).thenReturn(plan);
        when(feedService.snapshot(plan)).thenReturn(new FeedSnapshot("마르코의 블로그", "https://blog.java21.net/marco",
                "소개", "https://blog.java21.net/marco/rss", "https://blog.java21.net/marco/atom", "마르코", MODIFIED,
                List.of()));
    }
}
