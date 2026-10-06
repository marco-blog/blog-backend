package net.java21.blog.backend.auth.domain;

import java.time.Duration;
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
 * 리프레시 토큰(refresh_tokens, research R2). 원문은 저장하지 않고 SHA-256만 둔다.
 * 한 번의 로그인에서 회전으로 이어진 토큰은 같은 {@code familyId}를 가진다.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "family_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String familyId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    /** 유휴 만료(발급 + refresh-idle-ttl). */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 절대 만료(family 최초 발급 + refresh-absolute-ttl). */
    @Column(name = "family_expires_at", nullable = false)
    private Instant familyExpiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    /** 교체로 발급된 토큰 ID(외래 키 없음). */
    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
    }

    public RefreshToken(User user, String familyId, String tokenHash, Instant expiresAt, Instant familyExpiresAt) {
        this.user = user;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.familyExpiresAt = familyExpiresAt;
    }

    /** 새 로그인 계열의 첫 토큰. */
    public static RefreshToken first(User user, String familyId, String tokenHash, Instant now,
            Duration idleTtl, Duration absoluteTtl) {
        Instant familyExpiresAt = now.plus(absoluteTtl);
        return new RefreshToken(user, familyId, tokenHash, min(now.plus(idleTtl), familyExpiresAt), familyExpiresAt);
    }

    /** 회전으로 이어지는 다음 토큰. 절대 만료는 그대로 물려받는다. */
    public RefreshToken next(String tokenHash, Instant now, Duration idleTtl) {
        return new RefreshToken(user, familyId, tokenHash, min(now.plus(idleTtl), familyExpiresAt), familyExpiresAt);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt) || !now.isBefore(familyExpiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void markUsed(Instant now, Long replacedById) {
        this.usedAt = now;
        this.replacedById = replacedById;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getFamilyExpiresAt() {
        return familyExpiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Long getReplacedById() {
        return replacedById;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
