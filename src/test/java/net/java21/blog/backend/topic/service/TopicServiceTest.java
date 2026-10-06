package net.java21.blog.backend.topic.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.repository.TopicPostCountQueryRepository;
import net.java21.blog.backend.portal.service.PortalCache;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.portal.service.PortalCriteriaFactory;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.domain.TopicNames;
import net.java21.blog.backend.topic.dto.TopicNode;
import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.topic.repository.TopicRow;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 주제 서비스(T010, research P2, FR-147, SC-023): 선택 가능 검사(소분류·숨김 아님·부모 숨김 아님, 지금 값이면 생략)와 주제 탭 판단.
 */
@ExtendWith(MockitoExtension.class)
class TopicServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final TopicNames NAMES = new TopicNames("ko", "en", "ja", "zh");

    @Mock
    private TopicRepository topicRepository;
    @Mock
    private TopicQueryRepository topicQueryRepository;
    @Mock
    private TopicPostCountQueryRepository countRepository;
    @Mock
    private PortalCriteriaFactory criteriaFactory;
    @Mock
    private SystemSettingsService settings;

    private TopicService service;
    private Topic major;
    private Topic minor;

    @BeforeEach
    void setUp() {
        PortalProperties properties = PortalProperties.defaults();
        service = new TopicService(topicRepository, topicQueryRepository, countRepository, new PortalCache(properties),
                criteriaFactory, settings, properties);
        major = TestEntities.with(new Topic(null, "knowledge", NAMES, 0, "#3D7DD8", false), "id", 1L);
        minor = TestEntities.with(new Topic(major, "it-internet", NAMES, 0, null, false), "id", 2L);
    }

    @Test
    void selectableMinorIsReturned() {
        when(topicRepository.findWithParent(2L)).thenReturn(Optional.of(minor));

        assertThat(service.requireSelectable(2L, null)).isSameAs(minor);
        assertThat(service.requireSelectable(null, 2L)).isNull();
    }

    @Test
    void missingTopicIs404AndMajorOrHiddenIs422() {
        when(topicRepository.findWithParent(9L)).thenReturn(Optional.empty());
        when(topicRepository.findWithParent(1L)).thenReturn(Optional.of(major));
        when(topicRepository.findWithParent(2L)).thenReturn(Optional.of(minor));

        assertCode(() -> service.requireSelectable(9L, null), ErrorCode.TOPIC_NOT_FOUND);
        assertCode(() -> service.requireSelectable(1L, null), ErrorCode.TOPIC_NOT_SELECTABLE);
        minor.hide();
        assertCode(() -> service.requireSelectable(2L, 5L), ErrorCode.TOPIC_NOT_SELECTABLE);
        minor.unhide();
        major.hide();
        assertCode(() -> service.requireSelectable(2L, null), ErrorCode.TOPIC_NOT_SELECTABLE);
    }

    @Test
    void currentValueSkipsTheCheck() {
        minor.hide();
        when(topicRepository.getReferenceById(2L)).thenReturn(minor);

        assertThat(service.requireSelectable(2L, 2L)).isSameAs(minor);
        verify(topicRepository, never()).findWithParent(any());
    }

    @Test
    void minorOnTabWhenPinnedOrCountReachesThreshold() {
        List<TopicRow> rows = List.of(row(1L, null, "knowledge", false), row(11L, 1L, "it", false),
                row(12L, 1L, "mobile", true), row(13L, 1L, "science", false), row(2L, null, "sports", false),
                row(21L, 2L, "golf", false));
        Map<Long, Long> counts = Map.of(11L, 20L, 13L, 19L, 21L, 5L);

        List<TopicNode> tree = TopicService.buildTree(rows, counts, 20);

        assertThat(tree).extracting(TopicNode::slug).containsExactly("knowledge", "sports");
        TopicNode knowledge = tree.get(0);
        assertThat(knowledge.children()).extracting(TopicNode::slug, TopicNode::onTab)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("it", true),
                        org.assertj.core.groups.Tuple.tuple("mobile", true),
                        org.assertj.core.groups.Tuple.tuple("science", false));
        assertThat(knowledge.onTab()).isTrue();
        assertThat(knowledge.names()).containsEntry("zh-CN", "knowledge-zh");
        assertThat(knowledge.parentId()).isNull();
        assertThat(knowledge.children().get(0).parentId()).isEqualTo(1L);
        assertThat(knowledge.children().get(0).children()).isEmpty();
        assertThat(tree.get(1).onTab()).isFalse();
        assertThat(tree.get(1).children().get(0).onTab()).isFalse();
    }

    @Test
    void majorOnTabWhenSumReachesThresholdOrPinned() {
        List<TopicRow> rows = List.of(row(1L, null, "life", false), row(11L, 1L, "pets", false),
                row(12L, 1L, "cooking", false), row(2L, null, "sports", true), row(21L, 2L, "golf", false),
                row(3L, null, "empty", false));
        Map<Long, Long> counts = Map.of(11L, 10L, 12L, 10L);

        List<TopicNode> tree = TopicService.buildTree(rows, counts, 20);

        assertThat(tree.get(0).onTab()).isTrue();
        assertThat(tree.get(0).children()).allSatisfy(c -> assertThat(c.onTab()).isFalse());
        assertThat(tree.get(1).onTab()).isTrue();
        assertThat(tree.get(2).onTab()).isFalse();
        assertThat(tree.get(2).children()).isEmpty();
    }

    @Test
    void thresholdZeroShowsEverything() {
        List<TopicRow> rows = List.of(row(1L, null, "life", false), row(11L, 1L, "pets", false));

        List<TopicNode> tree = TopicService.buildTree(rows, Map.of(), 0);

        assertThat(tree.get(0).onTab()).isTrue();
        assertThat(tree.get(0).children().get(0).onTab()).isTrue();
    }

    @Test
    void publicTreeUsesVisibleRowsRecentCountsAndIsCached() {
        PortalCriteria criteria = new PortalCriteria(NOW, Duration.ofHours(24), 200);
        when(criteriaFactory.now()).thenReturn(criteria);
        when(settings.topicAutoHideThreshold()).thenReturn(1);
        when(topicQueryRepository.findVisible()).thenReturn(List.of(row(1L, null, "life", false),
                row(11L, 1L, "pets", false)));
        when(countRepository.countRecentByTopic(criteria, NOW.minus(Duration.ofDays(30)))).thenReturn(Map.of(11L, 1L));

        List<TopicNode> tree = service.publicTree();
        service.publicTree();

        assertThat(tree.get(0).children().get(0).onTab()).isTrue();
        verify(topicQueryRepository, times(1)).findVisible();
        assertThat(service.recentPostCounts()).containsEntry(11L, 1L);
        verify(countRepository, times(1)).countRecentByTopic(any(), any());
    }

    @Test
    void pageOfMajorCollectsVisibleChildrenAndHiddenIs404() {
        when(topicQueryRepository.findBySlug("knowledge")).thenReturn(Optional.of(row(1L, null, "knowledge", false)));
        when(topicQueryRepository.findVisibleChildIds(1L)).thenReturn(List.of(11L, 12L));
        when(topicQueryRepository.findBySlug("it")).thenReturn(Optional.of(row(11L, 1L, "it", false)));
        when(topicQueryRepository.findBySlug("nope")).thenReturn(Optional.empty());
        when(topicQueryRepository.findBySlug("hidden")).thenReturn(Optional.of(new TopicRow(13L, 1L, "hidden", "a", "b",
                "c", "d", 0, false, true, false, null, NOW, NOW)));

        assertThat(service.requirePage("knowledge")).isEqualTo(new TopicPage(1L, "knowledge", true, List.of(11L, 12L)));
        assertThat(service.requirePage("it")).isEqualTo(new TopicPage(11L, "it", false, List.of(11L)));
        assertCode(() -> service.requirePage("nope"), ErrorCode.TOPIC_NOT_FOUND);
        assertCode(() -> service.requirePage("hidden"), ErrorCode.TOPIC_NOT_FOUND);
    }

    private static TopicRow row(Long id, Long parentId, String slug, boolean pinned) {
        return new TopicRow(id, parentId, slug, slug + "-ko", slug + "-en", slug + "-ja", slug + "-zh", 0, false, false,
                pinned, parentId == null ? "#3D7DD8" : null, NOW, NOW);
    }
}
