package net.java21.blog.backend.external.thumbnail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 소유 인증이 커밋되면 썸네일 소급을 수집 풀에 맡긴다(007 research E7). 큐가 차면 건너뛴다(다음 수집의 새 글은 그대로 받음). */
@Component
public class ThumbnailBackfillListener {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailBackfillListener.class);

    private final ExternalThumbnailService thumbnails;
    private final TaskExecutor executor;

    public ThumbnailBackfillListener(ExternalThumbnailService thumbnails,
            @Qualifier("feedFetchExecutor") TaskExecutor executor) {
        this.thumbnails = thumbnails;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ThumbnailBackfillRequested event) {
        try {
            executor.execute(() -> {
                try {
                    thumbnails.backfill(event.externalBlogId());
                } catch (RuntimeException e) {
                    log.warn("Thumbnail backfill failed for external blog {}", event.externalBlogId(), e);
                }
            });
        } catch (TaskRejectedException e) {
            log.warn("Feed fetch queue is full; thumbnail backfill for {} skipped", event.externalBlogId());
        }
    }
}
