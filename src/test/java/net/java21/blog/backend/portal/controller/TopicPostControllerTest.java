package net.java21.blog.backend.portal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.service.TopicPostService;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 주제 페이지 글 API(003 T060): 비로그인 200 Page&lt;PortalCard&gt;, 404, 400. */
@WebMvcTest(TopicPostController.class)
@Import(WebMvcTestSupport.class)
class TopicPostControllerTest {

    private static final PortalCardResponse CARD = new PortalCardResponse(123L, "제목", "요약", null, 12L,
            new PortalCardResponse.BlogRef("marco", "블로그"), new PortalCardResponse.AuthorRef("마르코", null),
            Instant.parse("2026-10-06T04:24:19Z"), 0, 0);

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private TopicPostService topicPostService;

    @Test
    void anonymousGetsTopicPostsPage() throws Exception {
        when(topicPostService.posts("it-internet", "popular", "external", PageRequest.of(1, 50)))
                .thenReturn(new PageImpl<>(List.of(CARD), PageRequest.of(1, 50), 51));

        mvc.perform(get("/api/v1/topics/it-internet/posts").param("sort", "popular").param("page", "1")
                        .param("size", "100").param("source", "external"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(123))
                .andExpect(jsonPath("$.result[0].topicId").value(12))
                .andExpect(jsonPath("$.result[0].blog.handle").value("marco"))
                .andExpect(jsonPath("$.totalCount").value(51));
    }

    @Test
    void defaultsAreLatestFirstPageOf20() throws Exception {
        when(topicPostService.posts("life", null, null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/v1/topics/life/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isEmpty())
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    void unknownTopicIs404() throws Exception {
        when(topicPostService.posts(eq("nope"), eq(null), eq(null), eq(PageRequest.of(0, 20))))
                .thenThrow(new BusinessException(ErrorCode.TOPIC_NOT_FOUND, "x"));

        mvc.perform(get("/api/v1/topics/nope/posts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("TOPIC_NOT_FOUND"));
    }

    @Test
    void invalidSortOrPageIs400() throws Exception {
        when(topicPostService.posts("life", "oldest", null, PageRequest.of(0, 20)))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "x",
                        List.of(new FieldError("sort", "INVALID", Map.of("allowed", List.of("latest", "popular"))))));

        mvc.perform(get("/api/v1/topics/life/posts").param("sort", "oldest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("sort"))
                .andExpect(jsonPath("$.header.fieldErrors[0].params.allowed[1]").value("popular"));
        mvc.perform(get("/api/v1/topics/life/posts").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("page"));
    }
}
