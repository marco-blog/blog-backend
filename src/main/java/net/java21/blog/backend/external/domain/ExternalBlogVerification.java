package net.java21.blog.backend.external.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.user.domain.User;

/**
 * 소유 인증 코드(external_blog_verifications, 007 FR-110·129). 등록 전에도 발급하므로 대상은 피드 해시로 정한다. 신청·넘겨받기가 되면
 * 그 등록을 연결한다.
 */
@Entity
@Table(name = "external_blog_verifications")
public class ExternalBlogVerification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "feed_url_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String feedUrlHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "external_blog_id")
    private ExternalBlog externalBlog;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    protected ExternalBlogVerification() {
    }

    public ExternalBlogVerification(User user, String feedUrlHash, String code, Instant expiresAt) {
        this.user = user;
        this.feedUrlHash = feedUrlHash;
        this.code = code;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public void verify(Instant now) {
        this.verifiedAt = now;
    }

    public void link(ExternalBlog blog) {
        this.externalBlog = blog;
    }

    /** 이 회원의 성공한 인증이고 피드 해시가 같은지. */
    public boolean provesOwnership(Long userId, String feedHash) {
        return verifiedAt != null && user.getId().equals(userId) && feedUrlHash.equals(feedHash);
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getFeedUrlHash() {
        return feedUrlHash;
    }

    public ExternalBlog getExternalBlog() {
        return externalBlog;
    }

    public String getCode() {
        return code;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }
}
