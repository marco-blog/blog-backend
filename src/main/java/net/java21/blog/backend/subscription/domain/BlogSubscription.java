package net.java21.blog.backend.subscription.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.user.domain.User;

/**
 * 블로그 구독(blog_subscriptions, 002 FR-031·032). 회원·블로그 쌍마다 하나다. 쓰기는 {@code INSERT IGNORE}와 원자적 카운터 UPDATE로 한다
 * ({@code BlogSubscriptionRepository}, research D1). 외래 키에 {@code ON DELETE CASCADE}가 없으므로 회원 탈퇴·블로그 정리 때 서비스가 지운다.
 */
@Entity
@Table(name = "blog_subscriptions")
public class BlogSubscription {

    @EmbeddedId
    private BlogSubscriptionId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @MapsId("blogId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blog_id")
    private Blog blog;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BlogSubscription() {
    }

    public BlogSubscriptionId getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Blog getBlog() {
        return blog;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
