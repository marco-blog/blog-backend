package net.java21.blog.backend.export.domain;

import java.time.Duration;
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

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 블로그 백업(blog_exports, 004 FR-145, research B14). PENDING → RUNNING → READY(파일 경로·크기, 7일 뒤 만료) 또는 FAILED.
 * READY는 만료되면 정리 작업이 파일을 지우고 EXPIRED로 바꾼다. {@code file_path}는 {@code blog.export.dir} 기준 상대 경로이며
 * 응답에 내보내지 않는다.
 */
@Entity
@Table(name = "blog_exports")
public class BlogExport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blog_id", nullable = false)
    private Blog blog;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private ExportStatus status = ExportStatus.PENDING;

    @Column(name = "file_path", length = 300)
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected BlogExport() {
    }

    public BlogExport(Blog blog, User requestedBy) {
        this.blog = blog;
        this.requestedBy = requestedBy;
    }

    /** 만들기 끝: 파일 경로·크기와 완료·만료 시각(완료 + {@code retention}). */
    public void markReady(String filePath, long fileSize, Instant now, Duration retention) {
        this.status = ExportStatus.READY;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.errorCode = null;
        this.completedAt = now;
        this.expiresAt = now.plus(retention);
    }

    /** 실패(하루 제한에 세지 않는다). */
    public void markFailed(String errorCode) {
        this.status = ExportStatus.FAILED;
        this.errorCode = errorCode;
        this.filePath = null;
        this.fileSize = null;
    }

    /** 만료: 파일을 지운 뒤 부른다. */
    public void markExpired() {
        this.status = ExportStatus.EXPIRED;
        this.filePath = null;
    }

    public boolean isDownloadable(Instant now) {
        return status == ExportStatus.READY && filePath != null && expiresAt != null && expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public Blog getBlog() {
        return blog;
    }

    public User getRequestedBy() {
        return requestedBy;
    }

    public ExportStatus getStatus() {
        return status;
    }

    public String getFilePath() {
        return filePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
