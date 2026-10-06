package net.java21.blog.backend.post.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 글(posts, T096). 블로그 목록의 글 수와 블로그 삭제(글을 휴지통으로)에 필요해 US1 인증·블로그 단계에서 먼저 만들었다.
 * 작성·발행·휴지통 동작은 글 기능(T095~T103)에서 더한다.
 * {@code category_id}는 US2에서 카테고리 연관으로 바꾼다. 002~005가 더한 컬럼(like_count 등)은 DB 기본값이 있으므로 매핑하지 않는다.
 */
@Entity
@Table(name = "posts")
public class Post extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blog_id", nullable = false)
    private Blog blog;

    /** NULL=미분류. 카테고리 연관은 US2. */
    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "content_md", columnDefinition = "mediumtext")
    private String contentMarkdown;

    @Column(name = "content_html", columnDefinition = "mediumtext")
    private String contentHtml;

    @Column(name = "content_text", columnDefinition = "mediumtext")
    private String contentText;

    @Column(length = 300)
    private String summary;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private PostVisibility visibility = PostVisibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private PostStatus status = PostStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status_before_delete", length = 10)
    private PostStatus statusBeforeDelete;

    @Column(name = "comment_enabled", nullable = false)
    private boolean commentEnabled = true;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Column(name = "comment_count", nullable = false)
    private int commentCount;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Post() {
    }

    /** 새 임시저장 글(DRAFT). */
    public Post(Blog blog, String title) {
        this.blog = blog;
        this.title = title;
    }

    public Long getId() {
        return id;
    }

    public Blog getBlog() {
        return blog;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public String getContentMarkdown() {
        return contentMarkdown;
    }

    public String getContentHtml() {
        return contentHtml;
    }

    public String getContentText() {
        return contentText;
    }

    public String getSummary() {
        return summary;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public PostVisibility getVisibility() {
        return visibility;
    }

    public PostStatus getStatus() {
        return status;
    }

    public PostStatus getStatusBeforeDelete() {
        return statusBeforeDelete;
    }

    public boolean isCommentEnabled() {
        return commentEnabled;
    }

    public int getViewCount() {
        return viewCount;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
