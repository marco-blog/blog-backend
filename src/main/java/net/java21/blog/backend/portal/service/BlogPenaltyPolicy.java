package net.java21.blog.backend.portal.service;

import java.util.Collection;
import java.util.Map;

/**
 * 인기 점수의 블로그 감점(003 FR-086, research P4, 결정 표 9번). 신고·숨김 데이터는 005가 만들므로 003은 감점 없는 기본 구현만 둔다.
 * 005는 이 인터페이스의 다른 구현을 빈으로 등록해 바꾼다.
 */
public interface BlogPenaltyPolicy {

    /**
     * 블로그별 점수 배수(0~1). 결과에 없는 블로그는 1.0(감점 없음)이다. 한 번에 모든 후보 블로그를 받으므로 구현은 쿼리 1회로 계산한다.
     */
    Map<Long, Double> penalties(Collection<Long> blogIds, ScoreWeights weights);
}
