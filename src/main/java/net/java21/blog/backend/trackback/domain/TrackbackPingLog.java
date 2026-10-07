package net.java21.blog.backend.trackback.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.post.domain.Post;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 보낸 트랙백 기록(trackback_ping_logs, 005 FR-052). 발행·수정 때 주소마다 PENDING으로 만들고, 비동기 송신이 결과를 채운다.
 * {@code created_at}은 요청 시각, {@code attempted_at}은 실제로 보낸 시각이다.
 */
@Entity
@Table(name = "trackback_ping_logs")
public class TrackbackPingLog extends BaseTimeEntity {

    public static final int MESSAGE_MAX = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, updatable = false)
    private Post post;

    @Column(name = "target_url", nullable = false, length = Trackback.URL_MAX, updatable = false)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private PingStatus status = PingStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "error_code", length = 30)
    private PingErrorCode errorCode;

    @Column(name = "error_message", length = MESSAGE_MAX)
    private String errorMessage;

    @Column(name = "attempted_at")
    private Instant attemptedAt;

    protected TrackbackPingLog() {
    }

    public TrackbackPingLog(Post post, String targetUrl) {
        this.post = post;
        this.targetUrl = targetUrl;
    }

    public void succeed(Instant at) {
        this.status = PingStatus.SUCCESS;
        this.errorCode = null;
        this.errorMessage = null;
        this.attemptedAt = at;
    }

    public void fail(PingErrorCode code, String message, Instant at) {
        this.status = PingStatus.FAILED;
        this.errorCode = code;
        this.errorMessage = message == null || message.length() <= MESSAGE_MAX ? message
                : message.substring(0, MESSAGE_MAX);
        this.attemptedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Post getPost() {
        return post;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public PingStatus getStatus() {
        return status;
    }

    public PingErrorCode getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
