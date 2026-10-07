package net.java21.blog.backend.portal.service;

import java.time.Instant;
import java.util.Comparator;

/** 주제 페이지 합치기용 가벼운 행(007 research E13): 출처·id·발행 시각만. 그 페이지 몫만 카드로 읽는다. */
public record PortalKey(PortalSourceType source, Long id, Instant publishedAt) {

    /** {@link PortalItem#LATEST_ORDER}와 같은 순서. */
    public static final Comparator<PortalKey> LATEST_ORDER = Comparator
            .comparing(PortalKey::publishedAt, Comparator.reverseOrder())
            .thenComparing(PortalKey::source)
            .thenComparing(PortalKey::id, Comparator.reverseOrder());
}
