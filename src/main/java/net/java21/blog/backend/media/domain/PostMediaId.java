package net.java21.blog.backend.media.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code post_media} 복합 PK(post_id, media_id, source). */
@Embeddable
public record PostMediaId(
        @Column(name = "post_id", nullable = false) Long postId,
        @Column(name = "media_id", nullable = false) Long mediaId,
        @Enumerated(EnumType.STRING)
        @JdbcTypeCode(SqlTypes.VARCHAR)
        @Column(nullable = false, length = 10) PostMediaSource source) implements Serializable {
}
