package net.java21.blog.backend.external.classify;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 키워드 사전 자동 분류(007 research E9, 버전은 사전 파일의 {@code version}, 지금 {@code keyword-v1}).
 * <ul>
 *   <li>점수: 주제마다 일치한 낱말의 위치 가중치 합 — 피드 카테고리·태그 3, 제목 2, 요약 1. 같은 낱말은 위치마다 한 번만 센다.</li>
 *   <li>신뢰도: {@code (top / sum) × min(1, top / 4)} — 경쟁 주제가 없고 증거가 충분할수록 1. 일치가 없으면 주제 null·신뢰도 0.</li>
 *   <li>최고 점수가 같은 주제가 여럿이면 주제 id가 작은 것.</li>
 * </ul>
 */
@Component
public class KeywordTopicClassifier implements TopicClassifier {

    static final int TERM_WEIGHT = 3;
    static final int TITLE_WEIGHT = 2;
    static final int SUMMARY_WEIGHT = 1;
    static final double EVIDENCE = 4.0;

    private final KeywordDictionary dictionary;

    public KeywordTopicClassifier(KeywordDictionary dictionary) {
        this.dictionary = dictionary;
    }

    @Override
    public ClassificationResult classify(ClassifierInput input) {
        Field terms = Field.of(String.join(" , ", input.terms()));
        Field title = Field.of(input.title());
        Field summary = Field.of(input.summary());
        Map<Long, Integer> scores = new HashMap<>();
        for (Map.Entry<Long, List<String>> entry : dictionary.byTopicId().entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                score += (terms.contains(keyword) ? TERM_WEIGHT : 0) + (title.contains(keyword) ? TITLE_WEIGHT : 0)
                        + (summary.contains(keyword) ? SUMMARY_WEIGHT : 0);
            }
            if (score > 0) {
                scores.put(entry.getKey(), score);
            }
        }
        if (scores.isEmpty()) {
            return ClassificationResult.none(dictionary.version());
        }
        long sum = 0;
        Long topId = null;
        int top = 0;
        for (Map.Entry<Long, Integer> entry : scores.entrySet()) {
            sum += entry.getValue();
            if (entry.getValue() > top || (entry.getValue() == top && entry.getKey() < topId)) {
                top = entry.getValue();
                topId = entry.getKey();
            }
        }
        return new ClassificationResult(topId, confidence(top, sum), dictionary.version());
    }

    /** {@code (top / sum) × min(1, top / 4)}. */
    static double confidence(int top, long sum) {
        if (top <= 0 || sum <= 0) {
            return 0;
        }
        return ((double) top / sum) * Math.min(1.0, top / EVIDENCE);
    }

    /** 정규화한 한 위치의 텍스트와 토큰. */
    private record Field(String text, Set<String> tokens) {

        static Field of(String raw) {
            String text = KeywordNormalizer.normalize(raw);
            return new Field(text, KeywordNormalizer.tokens(text));
        }

        boolean contains(String keyword) {
            if (text.isEmpty()) {
                return false;
            }
            return KeywordNormalizer.isTokenKeyword(keyword) ? tokens.contains(keyword) : text.contains(keyword);
        }
    }
}
