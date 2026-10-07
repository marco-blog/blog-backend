package net.java21.blog.backend.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.dto.LatestSection;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.repository.PortalCardQueryRepository;
import net.java21.blog.backend.portal.repository.PortalCardRow;
import net.java21.blog.backend.portal.repository.PortalSectionQueryRepository;
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
import org.springframework.data.domain.PageRequest;

/**
 * 007 T043: 포털에 두 출처 합치기 — (발행 시각 내림, 같으면 INTERNAL 먼저, id 내림), 블로그당 2편 키 {@code P:}·{@code E:}, 커서의
 * 출처 {@code s}, {@code source} 필터와 캐시 키, 인기 목록 섞기, 주제 페이지 최신(두 출처 합친 순서·totalCount 합·깊이 상한)·인기,
 * 외부 출처가 없으면 003 그대로 (FR-123, FR-124, research E13).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortalMergeTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final PortalCriteria CRITERIA = new PortalCriteria(NOW, Duration.ofHours(24), 200);
    private static final TopicPage IT = new TopicPage(11L, "it-internet", false, List.of(11L));

    @Mock
    private PortalCriteriaFactory criteriaFactory;
    @Mock
    private PortalCardQueryRepository cards;
    @Mock
    private PortalSectionQueryRepository sections;
    @Mock
    private PopularityCalculator calculator;
    @Mock
    private ExternalPortalSource external;
    @Mock
    private TopicService topicService;
    @Mock
    private TopicPostQueryRepository topicPosts;

    private PortalService service;
    private TopicPostService topicPostService;

    @BeforeEach
    void setUp() {
        service = new PortalService(new PortalCache(PortalProperties.defaults()), criteriaFactory, cards, sections,
                calculator, PortalProperties.defaults(), external);
        topicPostService = new TopicPostService(topicService, topicPosts, cards, service, criteriaFactory,
                new PortalCache(PortalProperties.defaults()), external);
        when(external.enabled()).thenReturn(true);
        when(criteriaFactory.now()).thenReturn(CRITERIA);
        when(calculator.calculate(any())).thenReturn(new PopularitySnapshot(List.of()));
        when(cards.findLatest(any(), any(), anyInt())).thenReturn(List.of());
        when(external.latest(any(), any(), anyInt())).thenReturn(List.of());
        when(cards.findByIds(anyList(), any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> row(id, 1, 0)).toList();
        });
        when(external.cards(anyList(), any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> ext(id, 1, 0)).toList();
        });
        when(topicService.requirePage("it-internet")).thenReturn(IT);
    }

    private static PortalCardRow row(long id, long blogId, long secondsAgo) {
        return new PortalCardRow(id, "p" + id, "s", null, 11L, blogId, "h" + blogId, "b", "n", null,
                NOW.minusSeconds(secondsAgo), 0, 0);
    }

    private static PortalItem ext(long id, long blogId, long secondsAgo) {
        Instant at = NOW.minusSeconds(secondsAgo);
        return new PortalItem(PortalSourceType.EXTERNAL, id, blogId, at, PortalCardResponse.external(id, "e" + id,
                "s", null, 11L, new PortalCardResponse.ExternalBlogRef(blogId, "x", "x.example"), at));
    }

    private static List<String> labels(List<PortalCardResponse> cards) {
        return cards.stream().map(PortalCardResponse::title).toList();
    }

    @Test
    void latestMergesByTimeThenInternalFirstThenId() {
        when(cards.findLatest(any(), eq(null), anyInt())).thenReturn(List.of(row(5, 1, 10), row(3, 2, 30)));
        when(external.latest(any(), eq(null), anyInt())).thenReturn(List.of(ext(9, 1, 10), ext(4, 2, 20),
                ext(2, 3, 30)));

        LatestSection section = service.latest(null, "all");

        assertThat(labels(section.items())).containsExactly("p5", "e9", "e4", "p3", "e2");
        assertThat(section.nextCursor()).isNull();
    }

    @Test
    void perBlogCapKeepsInternalAndExternalBlogsApart() {
        // 내부 블로그 1과 외부 블로그 1은 id가 같아도 다른 블로그
        when(cards.findLatest(any(), eq(null), anyInt())).thenReturn(List.of(row(10, 1, 1), row(9, 1, 2),
                row(8, 1, 3)));
        when(external.latest(any(), eq(null), anyInt())).thenReturn(List.of(ext(7, 1, 4), ext(6, 1, 5),
                ext(5, 1, 6)));

        LatestSection section = service.latest(null, null);

        assertThat(labels(section.items())).containsExactly("p10", "p9", "e7", "e6");
    }

    @Test
    void cursorCarriesSourceAndContinuesBothSources() {
        List<PortalCardRow> internal = new ArrayList<>();
        List<PortalItem> externalRows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            internal.add(row(1000 - i, 100 + i, 2L * i));
            externalRows.add(ext(500 - i, 200 + i, 2L * i + 1));
        }
        when(cards.findLatest(any(), eq(null), anyInt())).thenReturn(internal);
        when(external.latest(any(), eq(null), anyInt())).thenReturn(externalRows);

        LatestSection first = service.latest(null, "all");

        assertThat(first.items()).hasSize(PortalService.LATEST_LIMIT);
        assertThat(first.items().get(19).title()).isEqualTo("e491");
        String json = new String(Base64.getUrlDecoder().decode(first.nextCursor()), StandardCharsets.UTF_8);
        assertThat(json).contains("\"s\":\"E\"").contains("\"i\":491");
        PortalCursor.Position position = PortalCursor.decode(first.nextCursor());
        assertThat(position.source()).isEqualTo(PortalSourceType.EXTERNAL);

        service.latest(first.nextCursor(), "all");
        verify(cards).findLatest(any(), eq(position), anyInt());
        verify(external).latest(any(), eq(position), anyInt());
    }

    @Test
    void sourceFilterReadsOnlyThatSourceAndIsPartOfCacheKey() {
        when(cards.findLatest(any(), eq(null), anyInt())).thenReturn(List.of(row(5, 1, 10)));
        when(external.latest(any(), eq(null), anyInt())).thenReturn(List.of(ext(9, 1, 10)));

        assertThat(labels(service.latest(null, "internal").items())).containsExactly("p5");
        assertThat(labels(service.latest(null, "EXTERNAL").items())).containsExactly("e9");
        assertThat(labels(service.latest(null, "all").items())).containsExactly("p5", "e9");
        // 캐시: 같은 필터는 다시 읽지 않는다
        service.latest(null, "internal");
        verify(external, org.mockito.Mockito.times(2)).latest(any(), eq(null), anyInt());
    }

    @Test
    void invalidSourceIs400WithAllowedValues() {
        assertThatThrownBy(() -> service.latest(null, "rss"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors().get(0).field()).isEqualTo("source");
                    assertThat(e.fieldErrors().get(0).params().get("allowed"))
                            .isEqualTo(List.of("all", "internal", "external"));
                });
    }

    @Test
    void homePopularMixesSourcesWithPerBlogCapAndCardsInRankOrder() {
        when(calculator.calculate(any())).thenReturn(new PopularitySnapshot(List.of(
                new PopularitySnapshot.Entry(1L, 7L, 11L, NOW, 9),
                new PopularitySnapshot.Entry(2L, 7L, 11L, NOW, 8, PortalSourceType.EXTERNAL),
                new PopularitySnapshot.Entry(3L, 7L, 11L, NOW, 7, PortalSourceType.EXTERNAL),
                new PopularitySnapshot.Entry(4L, 7L, 11L, NOW, 6, PortalSourceType.EXTERNAL),
                new PopularitySnapshot.Entry(5L, 7L, 11L, NOW, 5))));

        List<PortalCardResponse> popular = service.home().popular();

        assertThat(popular).extracting(PortalCardResponse::source, PortalCardResponse::id)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("INTERNAL", 1L),
                        org.assertj.core.groups.Tuple.tuple("EXTERNAL", 2L),
                        org.assertj.core.groups.Tuple.tuple("EXTERNAL", 3L),
                        org.assertj.core.groups.Tuple.tuple("INTERNAL", 5L));
    }

    @Test
    void cardsForDropsPostsThatAreNoLongerVisible() {
        when(external.cards(anyList(), any())).thenReturn(List.of());
        List<PortalCardResponse> out = PortalService.cardsFor(List.of(
                new PortalKey(PortalSourceType.EXTERNAL, 1L, NOW), new PortalKey(PortalSourceType.INTERNAL, 2L, NOW)),
                CRITERIA, cards, external);
        assertThat(out).extracting(PortalCardResponse::id).containsExactly(2L);
    }

    @Test
    void withoutExternalSourceLatestIsTheSameAs003() {
        PortalService plain = new PortalService(new PortalCache(PortalProperties.defaults()), criteriaFactory, cards,
                sections, calculator, PortalProperties.defaults());
        when(cards.findLatest(any(), eq(null), anyInt())).thenReturn(List.of(row(5, 1, 10), row(3, 1, 30),
                row(2, 1, 40)));

        assertThat(labels(plain.latest(null).items())).containsExactly("p5", "p3");
        assertThat(plain.latest(null, "external").items()).isEmpty();
    }

    @Test
    void topicLatestMergesKeysAndSumsTotals() {
        PageRequest page = PageRequest.of(0, 3);
        when(topicPosts.count(CRITERIA, IT.topicIds())).thenReturn(2L);
        when(external.topicCount(NOW, IT.topicIds())).thenReturn(3L);
        when(topicPosts.findLatestKeys(CRITERIA, IT.topicIds(), 3)).thenReturn(List.of(
                new PortalKey(PortalSourceType.INTERNAL, 8L, NOW.minusSeconds(10)),
                new PortalKey(PortalSourceType.INTERNAL, 7L, NOW.minusSeconds(40))));
        when(external.topicKeys(NOW, IT.topicIds(), 3)).thenReturn(List.of(
                new PortalKey(PortalSourceType.EXTERNAL, 30L, NOW.minusSeconds(10)),
                new PortalKey(PortalSourceType.EXTERNAL, 20L, NOW.minusSeconds(20)),
                new PortalKey(PortalSourceType.EXTERNAL, 10L, NOW.minusSeconds(50))));

        Page<PortalCardResponse> result = topicPostService.posts("it-internet", "latest", "all", page);

        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getContent()).extracting(PortalCardResponse::source, PortalCardResponse::id)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("INTERNAL", 8L),
                        org.assertj.core.groups.Tuple.tuple("EXTERNAL", 30L),
                        org.assertj.core.groups.Tuple.tuple("EXTERNAL", 20L));
    }

    @Test
    void topicLatestSecondPageAndDepthLimit() {
        when(topicPosts.count(any(), any())).thenReturn(0L);
        when(external.topicCount(any(), any())).thenReturn(5000L);
        when(external.topicKeys(NOW, IT.topicIds(), 4)).thenReturn(List.of(
                new PortalKey(PortalSourceType.EXTERNAL, 4L, NOW.minusSeconds(1)),
                new PortalKey(PortalSourceType.EXTERNAL, 3L, NOW.minusSeconds(2)),
                new PortalKey(PortalSourceType.EXTERNAL, 2L, NOW.minusSeconds(3)),
                new PortalKey(PortalSourceType.EXTERNAL, 1L, NOW.minusSeconds(4))));

        Page<PortalCardResponse> second = topicPostService.posts("it-internet", null, "external", PageRequest.of(1, 2));
        assertThat(second.getContent()).extracting(PortalCardResponse::id).containsExactly(2L, 1L);
        verify(topicPosts, never()).findLatestKeys(any(), any(), anyInt());

        Page<PortalCardResponse> deep = topicPostService.posts("it-internet", null, "all",
                PageRequest.of(TopicPostService.MAX_PAGE + 1, 20));
        assertThat(deep.getContent()).isEmpty();
        assertThat(deep.getTotalElements()).isEqualTo(5000);
    }

    @Test
    void topicInternalFilterUses003Query() {
        PageRequest page = PageRequest.of(0, 20);
        when(topicPosts.findLatest(CRITERIA, IT.topicIds(), page))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row(1, 1, 0)), page, 1));

        Page<PortalCardResponse> result = topicPostService.posts("it-internet", "latest", "internal", page);

        assertThat(result.getContent()).extracting(PortalCardResponse::id).containsExactly(1L);
        verify(external, never()).topicKeys(any(), any(), anyInt());
    }

    @Test
    void topicPopularFiltersSnapshotBySource() {
        when(calculator.calculate(any())).thenReturn(new PopularitySnapshot(List.of(
                new PopularitySnapshot.Entry(1L, 7L, 11L, NOW, 9),
                new PopularitySnapshot.Entry(2L, 7L, 11L, NOW, 8, PortalSourceType.EXTERNAL),
                new PopularitySnapshot.Entry(3L, 7L, 99L, NOW, 7, PortalSourceType.EXTERNAL))));

        assertThat(topicPostService.posts("it-internet", "popular", "external", PageRequest.of(0, 20)).getContent())
                .extracting(PortalCardResponse::id).containsExactly(2L);
        assertThat(topicPostService.posts("it-internet", "popular", "all", PageRequest.of(0, 20)).getContent())
                .extracting(PortalCardResponse::source).containsExactly("INTERNAL", "EXTERNAL");
    }
}
