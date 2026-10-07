package net.java21.blog.backend.block.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** {@link BlogBlock}의 복합 키(blog_id, blocked_user_id). */
@Embeddable
public record BlogBlockId(
        @Column(name = "blog_id") Long blogId,
        @Column(name = "blocked_user_id") Long blockedUserId) implements Serializable {
}
