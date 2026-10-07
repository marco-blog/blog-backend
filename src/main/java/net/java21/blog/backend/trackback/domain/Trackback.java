package net.java21.blog.backend.trackback.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import jakarta.persistence.UniqueConstraint;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.crypto.EncryptedStringConverter;
import net.java21.blog.backend.post.domain.Post;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 받은 트랙백(trackbacks, 005 FR-049~055). 같은 글에 같은 주소(정규화한 주소의 SHA-256)는 하나만 둔다
 * ({@code uk_trackbacks_post_source_url_hash}, H2에도 {@link UniqueConstraint}). 보낸 곳 IP는 AES-256-GCM 암호문이며
 * 90일 뒤 개인정보 파기 작업이 NULL로 지운다. 제목·요약·블로그 이름은 태그를 지운 일반 텍스트다.
 */
@Entity
@Table(name = "trackbacks", uniqueConstraints = @UniqueConstraint(name = "uk_trackbacks_post_source_url_hash",
        columnNames = {"post_id", "source_url_hash"}))
public class Trackback extends BaseTimeEntity {

    public static final int TEXT_MAX = 255;
    public static final int URL_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 트랙백을 받은 글. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, updatable = false)
    private Post post;

    /** 서비스 안의 글이 보낸 트랙백이면 그 글. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_post_id", updatable = false)
    private Post sourcePost;

    @Column(name = "source_url", nullable = false, length = URL_MAX, updatable = false)
    private String sourceUrl;

    @Column(name = "source_url_hash", nullable = false, length = 64, columnDefinition = "char(64)", updatable = false)
    private String sourceUrlHash;

    @Column(length = TEXT_MAX)
    private String title;

    @Column(length = TEXT_MAX)
    private String excerpt;

    @Column(name = "blog_name", length = TEXT_MAX)
    private String blogName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "sender_ip_enc", length = 128)
    private String senderIp;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private TrackbackStatus status = TrackbackStatus.ACTIVE;

    protected Trackback() {
    }

    public Trackback(Post post, Post sourcePost, String sourceUrl, String sourceUrlHash, String title, String excerpt,
            String blogName, String senderIp) {
        this.post = post;
        this.sourcePost = sourcePost;
        this.sourceUrl = sourceUrl;
        this.sourceUrlHash = sourceUrlHash;
        this.title = title;
        this.excerpt = excerpt;
        this.blogName = blogName;
        this.senderIp = senderIp;
    }

    public boolean isActive() {
        return status == TrackbackStatus.ACTIVE;
    }

    public boolean isHidden() {
        return status == TrackbackStatus.HIDDEN;
    }

    /** 글 주인 삭제(ACTIVE만). 행은 남겨 같은 주소의 재수신을 막는다. */
    public void markDeleted() {
        if (status != TrackbackStatus.ACTIVE) {
            throw new IllegalStateException("Only active trackbacks can be deleted: " + id);
        }
        this.status = TrackbackStatus.DELETED;
    }

    /** 관리자 숨김(005 FR-041). ACTIVE만 HIDDEN으로. @return 이번에 바뀌었으면 true */
    public boolean hide() {
        if (status != TrackbackStatus.ACTIVE) {
            return false;
        }
        this.status = TrackbackStatus.HIDDEN;
        return true;
    }

    /** 숨김 해제. HIDDEN만 ACTIVE로. @return 이번에 바뀌었으면 true */
    public boolean unhide() {
        if (status != TrackbackStatus.HIDDEN) {
            return false;
        }
        this.status = TrackbackStatus.ACTIVE;
        return true;
    }

    public Long getId() {
        return id;
    }

    public Post getPost() {
        return post;
    }

    public Post getSourcePost() {
        return sourcePost;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getSourceUrlHash() {
        return sourceUrlHash;
    }

    public String getTitle() {
        return title;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public String getBlogName() {
        return blogName;
    }

    public String getSenderIp() {
        return senderIp;
    }

    public TrackbackStatus getStatus() {
        return status;
    }
}
