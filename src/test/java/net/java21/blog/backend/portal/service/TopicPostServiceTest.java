package net.java21.blog.backend.portal.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.repository.PortalCardQueryRepository;
import net.java21.blog.backend.portal.repository.PortalCardRow;
import net.java21.blog.backend.portal.repository.TopicPostQueryRepository;
import net.java21.blog.backend.topic.service.TopicPage;
import net.java21.blog.backend.topic.service.TopicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 주제 페이지 글 목록(003 T059, FR-078 AS2·AS4, 결정 표 11번): slug 찾기(404), 최신순·인기순(스냅숏을 주제로 거른 순서의 페이지),
 * sort 허용 값, 캐시 키, 같은 블로그 제한 없음.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicPostServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final PortalCriteria CRITERIA = new PortalCriteria(NOW, Duration.ofHours(24), 200);
    private static final TopicPage LIFE = new TopicPage(1L, "life", true, List.of(11L, 12L));

    @Mock
    private TopicService topicService;
    @Mock
    private TopicPostQueryRepository topicPosts;
    @Mock
    private PortalCardQueryRepository cards;
    @Mock
    private PortalService portalService;
    @Mock
    private PortalCriteriaFactory criteriaFactory;

    private TopicPostService service;

    @BeforeEach
    void setUp() {
        service = new TopicPostService(topicService, topicPosts, cards, portalService, criteriaFactory,
                new PortalCache(PortalProperties.defaults()));
        when(criteriaFactory.now()).thenReturn(CRITERIA);
        when(topicService.requirePage("life")).thenReturn(LIFE);
        when(cards.findByIds(any(), any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> card(id, 1)).toList();
        });
    }

    @Test
    void latestPagesTheTopicPostsWithoutPerBlogCapAndIsCachedPerPage() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(topicPosts.findLatest(CRITERIA, LIFE.topicIds(), pageable))
                .thenReturn(new PageImpl<>(List.of(card(3, 1), card(2, 1), card(1, 1)), pageable, 3));

        Page<PortalCardResponse> page = service.posts("life", null, pageable);
        service.posts("life", "latest", pageable);

        assertThat(page.getContent()).extracting(PortalCardResponse::id).containsExactly(3L, 2L, 1L);
        assertThat(page.getTotalElements()).isEqualTo(3);
        verify(topicPosts, times(1)).findLatest(CRITERIA, LIFE.topicIds(), pageable);
        when(topicPosts.findLatest(CRITERIA, LIFE.topicIds(), PageRequest.of(1, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 20), 3));
        service.posts("life", "latest", PageRequest.of(1, 20));
        verify(topicPosts, times(1)).findLatest(CRITERIA, LIFE.topicIds(), PageRequest.of(1, 20));
    }

    @Test
    void popularPagesTheSnapshotFilteredByTopic() {
        when(portalService.popularity()).thenReturn(new PopularitySnapshot(List.of(
                entry(10, 11L, 9), entry(20, 99L, 8), entry(30, 12L, 7), entry(40, 11L, 6), entry(50, null, 5))));

        Page<PortalCardResponse> first = service.posts("life", "popular", PageRequest.of(0, 2));
        Page<PortalCardResponse> second = service.posts("life", "POPULAR", PageRequest.of(1, 2));
        Page<PortalCardResponse> beyond = service.posts("life", "popular", PageRequest.of(5, 2));

        assertThat(first.getContent()).extracting(PortalCardResponse::id).containsExactly(10L, 30L);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(second.getContent()).extracting(PortalCardResponse::id).containsExactly(40L);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(3);
        verify(topicPosts, never()).findLatest(any(), any(), any());
    }

    @Test
    void unknownOrHiddenTopicIs404() {
        when(topicService.requirePage("hidden")).thenThrow(new BusinessException(ErrorCode.TOPIC_NOT_FOUND, "x"));

        assertCode(() -> service.posts("hidden", "latest", PageRequest.of(0, 20)), ErrorCode.TOPIC_NOT_FOUND);
    }

    @Test
    void sortOutsideTheAllowedValuesIs400WithAllowedParams() {
        assertThatThrownBy(() -> service.posts("life", "oldest", PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).containsExactly(
                            new FieldError("sort", "INVALID", Map.of("allowed", List.of("latest", "popular"))));
                });
        verifyNoInteractions(topicService);
    }

    private static PopularitySnapshot.Entry entry(long postId, Long topicId, double score) {
        return new PopularitySnapshot.Entry(postId, 1L, topicId, NOW, score);
    }

    private static PortalCardRow card(long id, long blogId) {
        return new PortalCardRow(id, "t" + id, "s", null, 11L, blogId, "h", "b", "n", null, NOW, 0, 0);
    }
}
