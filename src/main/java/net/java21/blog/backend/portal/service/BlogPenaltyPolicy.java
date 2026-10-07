package net.java21.blog.backend.portal.service;

import java.util.Collection;
import java.util.Map;

/**
 * 인기 점수의 블로그 감점(003 FR-086, research P4, 결정 표 9번). 005가 인정된 신고 이력으로 감점하는 구현
 * ({@code ReportPenaltyPolicy})을 둔다.
 */
public interface BlogPenaltyPolicy {

    /**
     * 블로그별 점수 배수(0~1). 결과에 없는 블로그는 1.0(감점 없음)이다. 한 번에 모든 후보 블로그를 받으므로 구현은 쿼리 1회로 계산한다.
     */
    Map<Long, Double> penalties(Collection<Long> blogIds, ScoreWeights weights);
}
