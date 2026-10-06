package net.java21.blog.backend.media.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * 글과 이미지의 참조 관계(post_media, T214, FR-071·073). 발행본(PUBLISHED)과 작성 중 사본(DRAFT)을 따로 둔다.
 * 연관 대신 키만 두어 행을 바꿀 때 글·이미지를 읽지 않는다. 글을 영구 삭제하면 DB의 {@code ON DELETE CASCADE}로도 지워지지만
 * 정리 대상 판단을 위해 휴지통 비우기가 먼저 지운다.
 */
@Entity
@Table(name = "post_media")
public class PostMedia implements Persistable<PostMediaId> {

    @EmbeddedId
    private PostMediaId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 새 행은 SELECT 없이 바로 INSERT한다(키를 직접 정하므로 Spring Data가 merge하지 않게). */
    @Transient
    private boolean fresh = true;

    protected PostMedia() {
    }

    public PostMedia(Long postId, Long mediaId, PostMediaSource source, Instant createdAt) {
        this.id = new PostMediaId(postId, mediaId, source);
        this.createdAt = createdAt;
    }

    @Override
    public PostMediaId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.fresh = false;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
