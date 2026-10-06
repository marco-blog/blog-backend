package net.java21.blog.backend.subscription.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** {@code blog_subscriptions}의 복합 키(user_id, blog_id). */
@Embeddable
public record BlogSubscriptionId(@Column(name = "user_id") Long userId, @Column(name = "blog_id") Long blogId)
        implements Serializable {
}
