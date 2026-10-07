package net.java21.blog.backend.portal.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.repository.PopularityCandidateRow;
import net.java21.blog.backend.portal.repository.PopularitySignalQueryRepository;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인기 점수 계산(003 FR-086, research P4, 결정 표 3번).
 * 점수 = {@code (view·조회 + readComplete·끝까지 읽음 + like·좋아요 + comment·댓글) × 2^(-발행 후 시간/halfLifeHours) × 감점}.
 * 신호 네 가지를 각각 글별 쿼리 1회로 읽고 후보 글(포털 노출 글만)을 읽어 점수 &gt; 0인 글을 점수 순으로 담는다. 007 외부 글은
 * 클릭 수 × 외부 가중치 × 같은 감쇠로 같은 목록에 섞는다.
 */
@Component
public class PopularityCalculator {

    private static final double MILLIS_PER_HOUR = 3_600_000d;

    private final PopularitySignalQueryRepository signals;
    private final SystemSettingsService settings;
    private final BlogPenaltyPolicy penaltyPolicy;
    private final PortalProperties properties;
    private final ExternalPortalSource external;

    public PopularityCalculator(PopularitySignalQueryRepository signals, SystemSettingsService settings,
            BlogPenaltyPolicy penaltyPolicy, PortalProperties properties) {
        this(signals, settings, penaltyPolicy, properties, ExternalPortalSource.NONE);
    }

    @Autowired
    public PopularityCalculator(PopularitySignalQueryRepository signals, SystemSettingsService settings,
            BlogPenaltyPolicy penaltyPolicy, PortalProperties properties, ExternalPortalSource external) {
        this.signals = signals;
        this.settings = settings;
        this.penaltyPolicy = penaltyPolicy;
        this.properties = properties;
        this.external = external;
    }

    @Transactional(readOnly = true)
    public PopularitySnapshot calculate(PortalCriteria criteria) {
        Instant now = criteria.now();
        Duration window = properties.popularWindow();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        long days = Math.max(1, window.toDays());
        ScoreWeights weights = settings.scoreWeights();
        List<PopularitySnapshot.Entry> entries = new ArrayList<>(internalEntries(criteria, weights, today, days));
        entries.addAll(externalEntries(now, weights, today.minusDays(days - 1)));
        entries.sort(Comparator.comparingDouble(PopularitySnapshot.Entry::score).reversed()
                .thenComparing(PopularitySnapshot.Entry::publishedAt, Comparator.reverseOrder())
                .thenComparing(PopularitySnapshot.Entry::source)
                .thenComparing(PopularitySnapshot.Entry::postId, Comparator.reverseOrder()));
        return new PopularitySnapshot(entries);
    }

    private List<PopularitySnapshot.Entry> internalEntries(PortalCriteria criteria, ScoreWeights weights,
            LocalDate today, long days) {
        Instant now = criteria.now();
        Instant since = now.minus(properties.popularWindow());
        Map<Long, long[]> stats = signals.sumDailyStats(today.minusDays(days - 1));
        Map<Long, Long> likes = signals.countLikes(since);
        Map<Long, Long> comments = signals.countComments(since);
        Set<Long> ids = new HashSet<>(stats.keySet());
        ids.addAll(likes.keySet());
        ids.addAll(comments.keySet());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<PopularityCandidateRow> candidates = signals.findCandidates(criteria, ids);
        Map<Long, Double> penalties = candidates.isEmpty() ? Map.of()
                : penaltyPolicy.penalties(candidates.stream().map(PopularityCandidateRow::blogId).distinct().toList(),
                        weights);
        return candidates.stream()
                .map(row -> {
                    long[] daily = stats.getOrDefault(row.postId(), new long[2]);
                    double raw = weights.view() * daily[0] + weights.readComplete() * daily[1]
                            + weights.like() * likes.getOrDefault(row.postId(), 0L)
                            + weights.comment() * comments.getOrDefault(row.postId(), 0L);
                    double score = raw * decay(row.publishedAt(), now, weights.halfLifeHours())
                            * penalties.getOrDefault(row.blogId(), 1.0);
                    return new PopularitySnapshot.Entry(row.postId(), row.blogId(), row.topicId(), row.publishedAt(),
                            score);
                })
                .filter(entry -> entry.score() > 0)
                .toList();
    }

    /**
     * 외부 글 점수(007 FR-124, research E13) = 최근 7일 클릭 합 × {@code external.score-weight} × 같은 감쇠. 외부 블로그는 신고 감점
     * 대상이 아니다(차단·내림으로 처리). 가중치가 0이면 외부 글은 인기 목록에 없다(쿼리도 하지 않음).
     */
    private List<PopularitySnapshot.Entry> externalEntries(Instant now, ScoreWeights weights, LocalDate fromDay) {
        if (!external.enabled()) {
            return List.of();
        }
        double weight = settings.externalScoreWeight();
        if (weight <= 0) {
            return List.of();
        }
        return external.popularityCandidates(now, fromDay).stream()
                .map(row -> new PopularitySnapshot.Entry(row.id(), row.blogId(), row.topicId(), row.publishedAt(),
                        row.clicks() * weight * decay(row.publishedAt(), now, weights.halfLifeHours()),
                        PortalSourceType.EXTERNAL))
                .filter(entry -> entry.score() > 0)
                .toList();
    }

    /** {@code 2^(-발행 후 시간/반감기)}. 미래 발행 시각은 0시간으로, 반감기가 0 이하면 감쇠하지 않는다. */
    static double decay(Instant publishedAt, Instant now, double halfLifeHours) {
        if (halfLifeHours <= 0) {
            return 1.0;
        }
        double ageHours = Math.max(0, Duration.between(publishedAt, now).toMillis()) / MILLIS_PER_HOUR;
        return Math.pow(2, -ageHours / halfLifeHours);
    }
}
