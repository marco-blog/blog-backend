package net.java21.blog.backend.external.dto;

import java.time.Instant;

import net.java21.blog.backend.external.thumbnail.ExternalThumbnailService;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.domain.TopicSource;

/** 회원이 보는 수집된 글(007 contracts/api.md MyExternalPost). 썸네일은 소유 인증된 블로그만. */
public record MyExternalPostResponse(long id, String title, String summary, String link, String thumbnailUrl,
        Instant publishedAt, long topicId, TopicSource topicSource, ExternalPostStatus status,
        RemovedReason removedReason, int clickCount) {

    public static MyExternalPostResponse of(ExternalPost p, boolean verified) {
        return new MyExternalPostResponse(p.getId(), p.getTitle(), p.getSummary(), p.getLink(),
                thumbnailUrl(p, verified), p.getPublishedAt(), p.getTopic().getId(), p.getTopicSource(), p.getStatus(),
                p.getRemovedReason(), p.getClickCount());
    }

    /** {@code /media/external/{key}}(인증된 블로그이고 키가 있을 때만). */
    public static String thumbnailUrl(ExternalPost p, boolean verified) {
        return verified && p.getThumbnailKey() != null ? ExternalThumbnailService.urlOf(p.getThumbnailKey()) : null;
    }
}
