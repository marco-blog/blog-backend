package net.java21.blog.backend.external.fetch;

import java.time.Instant;
import java.util.Map;

import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.notification.service.ExternalBlogNotifier;
import org.springframework.stereotype.Component;

/**
 * 연속 실패 자동 중지(007 FR-117, research E5): 첫 실패 뒤 {@code stop-after}(7일) 동안 성공이 없으면 STOPPED로 바꾸고 관리 회원이
 * 있으면 알림 {@code EXTERNAL_FEED_STOPPED}({@code externalBlogTitle}, {@code lastResult}). 호출한 쪽 트랜잭션 안에서 부른다.
 */
@Component
public class FeedStopNotifier {

    private final ExternalFeedProperties properties;
    private final ExternalBlogNotifier notifier;

    public FeedStopNotifier(ExternalFeedProperties properties, ExternalBlogNotifier notifier) {
        this.properties = properties;
        this.notifier = notifier;
    }

    /** @return 이번에 멈췄으면 true */
    public boolean stopIfExpired(ExternalBlog blog, Instant now) {
        Instant first = blog.getFirstFailedAt();
        if (first == null || now.isBefore(first.plus(properties.stopAfter()))) {
            return false;
        }
        blog.stop();
        if (blog.getMember() != null) {
            notifier.notifyExternalBlog(blog.getMember().getId(), NotificationType.EXTERNAL_FEED_STOPPED, blog.getId(),
                    Map.of("externalBlogTitle", blog.displayTitle(), "lastResult",
                            blog.getLastFetchResult() == null ? "" : blog.getLastFetchResult().name()));
        }
        return true;
    }
}
