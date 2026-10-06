package net.java21.blog.backend.like.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** {@code post_likes}의 복합 키(user_id, post_id). */
@Embeddable
public record PostLikeId(@Column(name = "user_id") Long userId, @Column(name = "post_id") Long postId)
        implements Serializable {
}
