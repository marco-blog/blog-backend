package net.java21.blog.backend.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.repository.PopularityCandidateRow;
import net.java21.blog.backend.portal.repository.PopularitySignalQueryRepository;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 인기 점수(003 T035, FR-086, research P4): 가중합 × 반감기 감쇠 × 감점, 가중치는 운영 설정, 점수 0 제외, 점수 내림차순·같으면 발행
 * 최신순.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PopularityCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final PortalCriteria CRITERIA = new PortalCriteria(NOW, Duration.ofHours(24), 200);
    private static final ScoreWeights DEFAULT = new ScoreWeights(1, 5, 10, 8, 48, 0.5);

    @Mock
    private PopularitySignalQueryRepository signals;
    @Mock
    private SystemSettingsService settings;
    @Mock
    private BlogPenaltyPolicy penaltyPolicy;

    private final Map<Long, long[]> stats = new HashMap<>();
    private final Map<Long, Long> likes = new HashMap<>();
    private final Map<Long, Long> comments = new HashMap<>();
    private PopularityCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new PopularityCalculator(signals, settings, penaltyPolicy, PortalProperties.defaults());
        when(signals.sumDailyStats(LocalDate.parse("2026-09-30"))).thenReturn(stats);
        when(signals.countLikes(NOW.minus(Duration.ofDays(7)))).thenReturn(likes);
        when(signals.countComments(NOW.minus(Duration.ofDays(7)))).thenReturn(comments);
        when(settings.scoreWeights()).thenReturn(DEFAULT);
        when(penaltyPolicy.penalties(anyCollection(), any())).thenReturn(Map.of());
    }

    @Test
    void scoreIsWeightedSumTimesHalfLifeDecay() {
        stats.put(1L, new long[] {10, 2});
        likes.put(1L, 3L);
        comments.put(1L, 1L);
        candidates(row(1, 100, NOW.minus(Duration.ofHours(48))));

        PopularitySnapshot snapshot = calculator.calculate(CRITERIA);

        double raw = 1 * 10 + 5 * 2 + 10 * 3 + 8 * 1;
        assertThat(snapshot.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.postId()).isEqualTo(1L);
            assertThat(entry.score()).isCloseTo(raw / 2, within(1e-9));
        });
    }

    @Test
    void viewsAloneRankBelowReadCompletesAndLikesAndOlderPostsRankLower() {
        Instant hourAgo = NOW.minus(Duration.ofHours(1));
        stats.put(1L, new long[] {20, 0});
        stats.put(2L, new long[] {0, 5});
        likes.put(3L, 3L);
        likes.put(4L, 3L);
        candidates(row(1, 10, hourAgo), row(2, 20, hourAgo), row(3, 30, hourAgo),
                row(4, 40, NOW.minus(Duration.ofDays(5))));

        assertThat(ids(calculator.calculate(CRITERIA))).containsExactly(3L, 2L, 1L, 4L);
    }

    @Test
    void weightsComeFromSettingsAndChangeTheOrder() {
        Instant hourAgo = NOW.minus(Duration.ofHours(1));
        stats.put(1L, new long[] {20, 0});
        stats.put(2L, new long[] {0, 3});
        candidates(row(1, 10, hourAgo), row(2, 20, hourAgo));
        assertThat(ids(calculator.calculate(CRITERIA))).containsExactly(1L, 2L);

        when(settings.scoreWeights()).thenReturn(new ScoreWeights(1, 10, 10, 8, 48, 0.5));
        assertThat(ids(calculator.calculate(CRITERIA))).containsExactly(2L, 1L);
    }

    @Test
    void blogPenaltyMultipliesTheScore() {
        Instant hourAgo = NOW.minus(Duration.ofHours(1));
        likes.put(1L, 1L);
        likes.put(2L, 1L);
        candidates(row(1, 10, hourAgo), row(2, 20, hourAgo));
        when(penaltyPolicy.penalties(anyCollection(), any())).thenReturn(Map.of(20L, 0.5));

        List<PopularitySnapshot.Entry> entries = calculator.calculate(CRITERIA).entries();

        assertThat(entries).extracting(PopularitySnapshot.Entry::postId).containsExactly(1L, 2L);
        assertThat(entries.get(1).score()).isCloseTo(entries.get(0).score() / 2, within(1e-9));
    }

    @Test
    void zeroScoresAreDroppedAndTiesGoToTheNewerPost() {
        stats.put(1L, new long[] {1, 0});
        stats.put(2L, new long[] {1, 0});
        stats.put(3L, new long[] {0, 0});
        Instant at = NOW.minus(Duration.ofHours(1));
        candidates(row(1, 10, at.minusSeconds(60)), row(2, 20, at), row(3, 30, at));
        when(settings.scoreWeights()).thenReturn(new ScoreWeights(1, 5, 10, 8, 0, 0.5));

        assertThat(ids(calculator.calculate(CRITERIA))).containsExactly(2L, 1L);
    }

    @Test
    void noSignalsMeansEmptySnapshotWithoutReadingCandidates() {
        assertThat(calculator.calculate(CRITERIA).entries()).isEmpty();
        verify(signals, never()).findCandidates(any(), anyCollection());
    }

    @Test
    void decayTreatsFuturePublishedAtAsZeroAge() {
        assertThat(PopularityCalculator.decay(NOW.plusSeconds(60), NOW, 48)).isEqualTo(1.0);
        assertThat(PopularityCalculator.decay(NOW.minus(Duration.ofHours(96)), NOW, 48)).isCloseTo(0.25,
                within(1e-9));
    }

    @Test
    void snapshotFiltersByTopicKeepingOrder() {
        PopularitySnapshot snapshot = new PopularitySnapshot(List.of(
                new PopularitySnapshot.Entry(1L, 10L, 5L, NOW, 3),
                new PopularitySnapshot.Entry(2L, 10L, null, NOW, 2),
                new PopularitySnapshot.Entry(3L, 10L, 6L, NOW, 1)));
        assertThat(snapshot.forTopics(List.of(6L, 5L))).extracting(PopularitySnapshot.Entry::postId)
                .containsExactly(1L, 3L);
    }

    private PopularityCalculator withExternal(ExternalPortalSource external, double weight) {
        when(external.enabled()).thenReturn(true);
        when(settings.externalScoreWeight()).thenReturn(weight);
        return new PopularityCalculator(signals, settings, penaltyPolicy, PortalProperties.defaults(), external);
    }

    @Test
    void externalScoreIsClicksTimesWeightTimesSameDecayInOneList() {
        ExternalPortalSource external = org.mockito.Mockito.mock(ExternalPortalSource.class);
        when(external.popularityCandidates(NOW, LocalDate.parse("2026-09-30"))).thenReturn(List.of(
                new ExternalPortalSource.PopularityCandidate(7L, 70L, 11L, NOW.minus(Duration.ofHours(48)), 40),
                new ExternalPortalSource.PopularityCandidate(8L, 70L, 11L, NOW, 0)));
        stats.put(1L, new long[] {15, 0});
        candidates(row(1, 100, NOW));
        PopularityCalculator mixed = withExternal(external, 1.5);

        PopularitySnapshot snapshot = mixed.calculate(CRITERIA);

        assertThat(snapshot.entries()).extracting(PopularitySnapshot.Entry::source, PopularitySnapshot.Entry::postId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(PortalSourceType.EXTERNAL, 7L),
                        org.assertj.core.groups.Tuple.tuple(PortalSourceType.INTERNAL, 1L));
        assertThat(snapshot.entries().get(0).score()).isCloseTo(40 * 1.5 / 2, within(1e-9));
        assertThat(snapshot.entries().get(0).capKey()).isEqualTo("E:70");
        assertThat(snapshot.entries().get(1).capKey()).isEqualTo("P:100");
        // 외부 블로그는 신고 감점 대상이 아니다
        org.mockito.Mockito.verify(penaltyPolicy).penalties(List.of(100L), DEFAULT);
    }

    @Test
    void zeroExternalWeightLeavesExternalPostsOut() {
        ExternalPortalSource external = org.mockito.Mockito.mock(ExternalPortalSource.class);
        PopularityCalculator mixed = withExternal(external, 0);

        assertThat(mixed.calculate(CRITERIA).entries()).isEmpty();
        org.mockito.Mockito.verify(external, org.mockito.Mockito.never()).popularityCandidates(any(), any());
    }

    @Test
    void externalOnlyWorksWithoutInternalSignals() {
        ExternalPortalSource external = org.mockito.Mockito.mock(ExternalPortalSource.class);
        when(external.popularityCandidates(any(), any())).thenReturn(List.of(
                new ExternalPortalSource.PopularityCandidate(7L, 70L, 11L, NOW, 2)));
        PopularitySnapshot snapshot = withExternal(external, 1).calculate(CRITERIA);

        assertThat(snapshot.forTopics(List.of(11L), PortalSourceFilter.EXTERNAL)).hasSize(1);
        assertThat(snapshot.forTopics(List.of(11L), PortalSourceFilter.INTERNAL)).isEmpty();
    }

    private void candidates(PopularityCandidateRow... rows) {
        when(signals.findCandidates(any(), anyCollection())).thenReturn(List.of(rows));
    }

    private static PopularityCandidateRow row(long postId, long blogId, Instant publishedAt) {
        return new PopularityCandidateRow(postId, blogId, null, publishedAt);
    }

    private static List<Long> ids(PopularitySnapshot snapshot) {
        return snapshot.entries().stream().map(PopularitySnapshot.Entry::postId).toList();
    }
}
