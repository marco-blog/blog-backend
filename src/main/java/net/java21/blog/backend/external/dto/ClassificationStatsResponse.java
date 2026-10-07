package net.java21.blog.backend.external.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.external.domain.TopicSource;

/**
 * 분류 현황(007 contracts/api.md ClassificationStats, FR-122, research E11). {@code rate}는 표본이 0이면 null.
 * {@code classifierAccuracy}: 최근 30일 사람이 정한(주인·검수) 글 중 분류기 예측이 최종 주제와 같은 비율.
 * {@code finalAccuracy}: 최근 30일 확정한 검수 중 확정 주제가 블로그 기본 주제(검수 대기 동안 노출되던 주제)와 같은 비율(SC-019 근사).
 */
public record ClassificationStatsResponse(Window window, Accuracy classifierAccuracy, FinalAccuracy finalAccuracy,
        List<TopicCount> distribution, long pendingReviews, double minConfidence, String classifierVersion,
        Instant generatedAt) {

    public record Window(Instant from, Instant to) {
    }

    public record Accuracy(long sample, long correct, Double rate) {
    }

    public record FinalAccuracy(long sample, long unchanged, Double rate) {
    }

    public record TopicCount(long topicId, long total, Map<TopicSource, Long> bySource) {
    }

    public static Double rate(long part, long sample) {
        return sample == 0 ? null : (double) part / sample;
    }
}
