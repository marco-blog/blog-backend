package net.java21.blog.backend.blog.domain;

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
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 블로그(blogs). 회원 1 : 블로그 N(R28). {@code handle}은 삭제된 블로그를 포함해 유일하고 바꿀 수 없다(FR-002, FR-159).
 * 002~007이 더한 컬럼(subscriber_count, portal_enabled 등)은 DB 기본값이 있으므로 매핑하지 않는다.
 */
@Entity
@Table(name = "blogs")
public class Blog extends BaseTimeEntity {

    public static final int TITLE_MAX = 100;
    public static final int DESCRIPTION_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 대표 이미지(media). 미디어 엔티티는 US4에서 연결한다. */
    @Column(name = "cover_media_id")
    private Long coverMediaId;

    @Column(nullable = false, unique = true, length = 20, updatable = false)
    private String handle;

    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    @Column(name = "comment_enabled", nullable = false)
    private boolean commentEnabled = true;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private BlogStatus status = BlogStatus.ACTIVE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Blog() {
    }

    public Blog(User user, String handle, String title) {
        this.user = user;
        this.handle = handle;
        this.title = title;
    }

    /** 제목을 주지 않았을 때의 기본 제목. */
    public static String defaultTitle(String nickname) {
        return nickname + "의 블로그";
    }

    public boolean isActive() {
        return status == BlogStatus.ACTIVE;
    }

    public void delete(Instant now) {
        this.status = BlogStatus.DELETED;
        this.deletedAt = now;
    }

    public void changeTitle(String title) {
        this.title = title;
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void changeCommentEnabled(boolean commentEnabled) {
        this.commentEnabled = commentEnabled;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Long getCoverMediaId() {
        return coverMediaId;
    }

    public String getHandle() {
        return handle;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public boolean isCommentEnabled() {
        return commentEnabled;
    }

    public BlogStatus getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
