package net.java21.blog.backend.post.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/**
 * 글 작성 중 사본(post_drafts, T097, FR-016·108). 발행 전 글의 작성본이거나, 발행된 글을 고치는 동안의 작성본이다.
 * 발행 때 내용을 {@link Post}에 반영하고 지운다. 글을 영구 삭제하면 DB의 {@code ON DELETE CASCADE}로 함께 지워진다.
 * {@code category_id}는 외래 키 없이 저장하고 발행 때 검증한다(US2). 003의 {@code topic_id}는 매핑하지 않는다.
 */
@Entity
@Table(name = "post_drafts")
public class PostDraft extends BaseTimeEntity {

    @Id
    @Column(name = "post_id")
    private Long postId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Post post;

    @Column(length = 200)
    private String title;

    @Column(name = "content_md", columnDefinition = "mediumtext")
    private String contentMarkdown;

    @Column(name = "category_id")
    private Long categoryId;

    /** 작성 중 태그 목록(JSON 배열). 정규화·검증은 발행 때(US2). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags_json")
    private List<String> tags = new ArrayList<>();

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    protected PostDraft() {
    }

    public PostDraft(Post post) {
        this.post = post;
    }

    /** 자동저장·임시저장. 발행본에는 영향이 없다. */
    public void write(String title, String contentMarkdown, Long categoryId, List<String> tags, Instant now) {
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.categoryId = categoryId;
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        this.savedAt = now;
    }

    public Long getPostId() {
        return postId;
    }

    public Post getPost() {
        return post;
    }

    public String getTitle() {
        return title;
    }

    public String getContentMarkdown() {
        return contentMarkdown;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public List<String> getTags() {
        return tags == null ? List.of() : List.copyOf(tags);
    }

    public Instant getSavedAt() {
        return savedAt;
    }
}
