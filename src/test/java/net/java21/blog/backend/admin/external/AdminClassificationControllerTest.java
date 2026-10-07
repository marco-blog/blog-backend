package net.java21.blog.backend.admin.external;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.ClassificationReviewResponse;
import net.java21.blog.backend.external.dto.ClassificationStatsResponse;
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

/** 007 T064: 검수 목록·확정·일괄 확정, 분류 현황 API — 검증·오류 코드·{@code no-store}, 관리자 외 404. */
@WebMvcTest(AdminClassificationController.class)
@Import(WebMvcTestSupport.class)
class AdminClassificationControllerTest {

    private static final long ADMIN = 5L;
    private static final Instant AT = Instant.parse("2026-10-07T00:00:00Z");
    private static final ClassificationReviewResponse REVIEW = new ClassificationReviewResponse(41L,
            ReviewStatus.PENDING,
            new ClassificationReviewResponse.PostRef(31L, "Title", null, "https://remote.example/1", List.of("java"),
                    4L, TopicSource.DEFAULT),
            new ClassificationReviewResponse.BlogRef(11L, "Remote", 4L), null, new BigDecimal("0.250"), null, null,
            null, AT);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private ClassificationReviewService reviewService;
    @MockitoBean
    private ClassificationStatsService statsService;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void listsReviews() throws Exception {
        when(reviewService.list(isNull(), eq(11L), any()))
                .thenReturn(new PageImpl<>(List.of(REVIEW), PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/v1/admin/classification-reviews?externalBlogId=11").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.result[0].post.feedTerms[0]").value("java"))
                .andExpect(jsonPath("$.result[0].externalBlog.defaultTopicId").value(4))
                .andExpect(jsonPath("$.result[0].confidence").value(0.25));
        mvc.perform(get("/api/v1/admin/classification-reviews?status=NOPE").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmsOneAndBatch() throws Exception {
        when(reviewService.confirm(eq(ADMIN), eq(41L), eq(7L), anyString())).thenReturn(REVIEW);
        mvc.perform(post("/api/v1/admin/classification-reviews/41/confirm").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"topicId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(41));

        when(reviewService.confirm(eq(ADMIN), eq(42L), eq(7L), anyString())).thenThrow(BusinessException.withParams(
                ErrorCode.CLASSIFICATION_REVIEW_CLOSED, "x", Map.of("status", "CONFIRMED")));
        mvc.perform(post("/api/v1/admin/classification-reviews/42/confirm").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"topicId\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("CLASSIFICATION_REVIEW_CLOSED"))
                .andExpect(jsonPath("$.header.params.status").value("CONFIRMED"));

        when(reviewService.confirmBatch(eq(ADMIN), eq(List.of(new ClassificationReviewService.Item(41L, 7L),
                new ClassificationReviewService.Item(42L, 8L))), anyString()))
                .thenReturn(new ClassificationReviewService.BatchResult(List.of(41L),
                        List.of(new ClassificationReviewService.Skipped(42L, "CONFIRMED"))));
        mvc.perform(post("/api/v1/admin/classification-reviews/confirm-batch").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"id\":41,\"topicId\":7},{\"id\":42,\"topicId\":8}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.confirmed[0]").value(41))
                .andExpect(jsonPath("$.result.skipped[0].status").value("CONFIRMED"));
    }

    @Test
    void stats() throws Exception {
        Map<TopicSource, Long> bySource = new EnumMap<>(TopicSource.class);
        bySource.put(TopicSource.AUTO, 3L);
        when(statsService.stats()).thenReturn(new ClassificationStatsResponse(
                new ClassificationStatsResponse.Window(AT.minusSeconds(86400 * 30L), AT),
                new ClassificationStatsResponse.Accuracy(0, 0, null),
                new ClassificationStatsResponse.FinalAccuracy(4, 3, 0.75),
                List.of(new ClassificationStatsResponse.TopicCount(4L, 3, bySource)), 2, 0.7, "keyword-v1", AT));
        mvc.perform(get("/api/v1/admin/classification-stats").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.result.classifierAccuracy.sample").value(0))
                .andExpect(jsonPath("$.result.finalAccuracy.rate").value(0.75))
                .andExpect(jsonPath("$.result.distribution[0].bySource.AUTO").value(3))
                .andExpect(jsonPath("$.result.pendingReviews").value(2))
                .andExpect(jsonPath("$.result.minConfidence").value(0.7));
    }

    @Test
    void nonAdminGets404() throws Exception {
        mvc.perform(get("/api/v1/admin/classification-stats").cookie(authCookies.user(6L)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/classification-reviews/41/confirm").cookie(authCookies.user(6L))
                .contentType(MediaType.APPLICATION_JSON).content("{\"topicId\":7}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(reviewService, statsService);
    }
}
