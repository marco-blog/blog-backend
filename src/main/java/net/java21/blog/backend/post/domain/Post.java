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
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.common.domain.BaseTimeEntity;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 글(posts, T096). 상태 전이(data-model "상태 전이"): DRAFT → PUBLISHED(발행), PUBLISHED → DRAFT 불가,
 * DRAFT/PUBLISHED → DELETED(휴지통, 직전 상태 보관), DELETED → 직전 상태(복구, FR-084).
 * 작성 중 내용은 {@link PostDraft}에 두고 발행 때 이 행에 반영한다(FR-108).
 * 카테고리는 LAZY 연관이며 목록 조회는 DTO projection으로 읽는다(N+1 없음). 002의 좋아요 수({@code like_count})는 읽기 전용으로 매핑하고
 * (좋아요·취소의 원자적 UPDATE로만 바뀐다), 003~005가 더한 컬럼(topic_id 등)은 DB 기본값이 있으므로 매핑하지 않는다.
 * 노출 판단은 {@code PostExposure} 한 곳에서 한다.
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

    /** NULL=미분류(FR-024). 같은 블로그의 카테고리만 넣는다(서비스 검증). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

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

    /** 좋아요 수(FR-030). 엔티티 저장으로 바꾸지 않는다(좋아요·취소의 원자적 UPDATE만, 002 research D1). */
    @ColumnDefault("0")
    @Column(name = "like_count", nullable = false, insertable = false, updatable = false)
    private int likeCount;

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

    public boolean isDeleted() {
        return status == PostStatus.DELETED;
    }

    public boolean isPublished() {
        return status == PostStatus.PUBLISHED;
    }

    /** 이 글이 속한 블로그의 주인인지. 블로그와 주인이 읽혀 있어야 한다. */
    public boolean isOwnedBy(Long userId) {
        return userId != null && blog.getUser().getId().equals(userId);
    }

    /** 발행 전 글의 제목을 작성 중 사본과 맞춘다(블로그 관리 목록에 보이는 제목). 발행된 글은 발행 때만 바뀐다. */
    public void syncDraftTitle(String draftTitle) {
        if (status == PostStatus.DRAFT) {
            this.title = draftTitle == null ? "" : draftTitle;
        }
    }

    /**
     * 발행(수정 발행 포함). 변환·살균된 내용을 반영하고 PUBLISHED로 바꾼다. {@code published_at}은 처음 발행할 때만 정한다.
     * 글 번호(id)는 바뀌지 않는다.
     */
    public void publish(String title, String markdown, String html, String text, String summary, String thumbnailUrl,
            PostVisibility visibility, boolean commentEnabled, Instant now) {
        if (status == PostStatus.DELETED) {
            throw new IllegalStateException("Deleted post cannot be published: " + id);
        }
        this.title = title;
        this.contentMarkdown = markdown;
        this.contentHtml = html;
        this.contentText = text;
        this.summary = summary;
        this.thumbnailUrl = thumbnailUrl;
        this.visibility = visibility;
        this.commentEnabled = commentEnabled;
        this.status = PostStatus.PUBLISHED;
        if (publishedAt == null) {
            this.publishedAt = now;
        }
    }

    /** 카테고리 지정(발행, FR-024). null이면 미분류. */
    public void classify(Category category) {
        this.category = category;
    }

    /** 휴지통으로(FR-084). 직전 상태를 남긴다. */
    public void moveToTrash(Instant now) {
        if (status == PostStatus.DELETED) {
            throw new IllegalStateException("Post already in trash: " + id);
        }
        this.statusBeforeDelete = status;
        this.status = PostStatus.DELETED;
        this.deletedAt = now;
    }

    /** 휴지통에서 삭제 전 상태로(FR-084). 공개 범위는 삭제 중에도 바뀌지 않았으므로 그대로다. */
    public void restore() {
        if (status != PostStatus.DELETED) {
            throw new IllegalStateException("Post not in trash: " + id);
        }
        this.status = statusBeforeDelete == null ? PostStatus.DRAFT : statusBeforeDelete;
        this.statusBeforeDelete = null;
        this.deletedAt = null;
    }

    public Long getId() {
        return id;
    }

    public Blog getBlog() {
        return blog;
    }

    /** 카테고리(LAZY). 미분류면 null. */
    public Category getCategory() {
        return category;
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

    public int getLikeCount() {
        return likeCount;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
