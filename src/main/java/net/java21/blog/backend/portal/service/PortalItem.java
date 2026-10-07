package net.java21.blog.backend.portal.service;

import java.time.Instant;
import java.util.Comparator;

import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.repository.PortalCardRow;

/**
 * 두 출처를 합칠 때의 카드 한 장(007 research E13). {@code blogId}는 출처 안의 블로그 id(내부 {@code blogs.id}, 외부
 * {@code external_blogs.id})라 블로그당 2편 제한 키는 {@link #capKey()}로 나눈다.
 */
public record PortalItem(PortalSourceType source, Long id, Long blogId, Instant publishedAt, PortalCardResponse card) {

    /** 발행 시각 내림, 같으면 INTERNAL 먼저, 같으면 id 내림(research E13). */
    public static final Comparator<PortalItem> LATEST_ORDER = Comparator
            .comparing(PortalItem::publishedAt, Comparator.reverseOrder())
            .thenComparing(PortalItem::source)
            .thenComparing(PortalItem::id, Comparator.reverseOrder());

    public static PortalItem internal(PortalCardRow row) {
        return new PortalItem(PortalSourceType.INTERNAL, row.id(), row.blogId(), row.publishedAt(),
                PortalCardResponse.from(row));
    }

    /** 블로그당 2편 제한 키: {@code P:{blogId}}·{@code E:{externalBlogId}}. */
    public String capKey() {
        return source.code() + ":" + blogId;
    }

    public PortalCursor.Position position() {
        return new PortalCursor.Position(publishedAt, id, source);
    }
}
