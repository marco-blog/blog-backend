package net.java21.blog.backend.external.thumbnail;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 외부 글 삭제가 커밋된 뒤에만 썸네일 파일을 지운다(롤백되면 파일이 남아 있어야 한다). */
@Component
public class ThumbnailDeleteListener {

    private final ExternalThumbnailService thumbnails;

    public ThumbnailDeleteListener(ExternalThumbnailService thumbnails) {
        this.thumbnails = thumbnails;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ThumbnailDeleteRequested event) {
        thumbnails.delete(event.keys());
    }
}
