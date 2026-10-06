package net.java21.blog.backend.portal.service;

import java.util.Collection;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 감점 없음(항상 1.0). 005가 신고·숨김 이력으로 감점하는 구현으로 바꾼다. */
@Component
public class NoBlogPenaltyPolicy implements BlogPenaltyPolicy {

    @Override
    public Map<Long, Double> penalties(Collection<Long> blogIds, ScoreWeights weights) {
        return Map.of();
    }
}
