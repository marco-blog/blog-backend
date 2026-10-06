package net.java21.blog.backend.portal.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인기 점수 가중치(003 FR-086, research P4, 결정 표 3번).
 * 점수 = {@code (view·조회 + readComplete·끝까지 읽음 + like·좋아요 + comment·댓글) × 2^(-발행 후 시간/halfLifeHours) × 감점}.
 * 기본값은 프로퍼티 {@code blog.portal.score-weights.*}, 운영 중에는 설정 {@code portal.score-weights}가 우선한다.
 *
 * @param reportPenalty 신고·숨김 이력이 있는 블로그에 곱하는 값(0~1, 005가 쓴다)
 */
public record ScoreWeights(
        @DefaultValue("1") double view,
        @DefaultValue("5") double readComplete,
        @DefaultValue("10") double like,
        @DefaultValue("8") double comment,
        @DefaultValue("48") double halfLifeHours,
        @DefaultValue("0.5") double reportPenalty) {

    /** JSON 필드 이름(설정 값 {@code portal.score-weights}). */
    public static final String[] FIELDS = {"view", "readComplete", "like", "comment", "halfLifeHours", "reportPenalty"};

    /** 설정 값(JSON 객체, 이미 검증됨)에서 읽는다. */
    public static ScoreWeights fromJson(Map<?, ?> json) {
        return new ScoreWeights(number(json, "view"), number(json, "readComplete"), number(json, "like"),
                number(json, "comment"), number(json, "halfLifeHours"), number(json, "reportPenalty"));
    }

    /** 설정 값(JSON 객체) 모양. 정수로 떨어지는 값은 정수로 쓴다. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("view", plain(view));
        json.put("readComplete", plain(readComplete));
        json.put("like", plain(like));
        json.put("comment", plain(comment));
        json.put("halfLifeHours", plain(halfLifeHours));
        json.put("reportPenalty", plain(reportPenalty));
        return json;
    }

    private static double number(Map<?, ?> json, String field) {
        Object value = json.get(field);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("Missing score weight: " + field);
    }

    private static Number plain(double value) {
        return value == Math.rint(value) && Math.abs(value) < Integer.MAX_VALUE ? (Number) (int) value : (Number) value;
    }
}
