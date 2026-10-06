package net.java21.blog.backend.tag.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import net.java21.blog.backend.post.domain.Post;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 글 태그 연결(post_tags, T177). 글당 최대 10개(FR-025, 서비스 검증). 글을 영구 삭제하면 DB의 {@code ON DELETE CASCADE}로 함께 지워진다.
 * 테이블에 {@code updated_at}이 없어 {@code BaseTimeEntity}를 쓰지 않는다. 키를 직접 정하므로 {@link Persistable}로 새 행임을 알려
 * 저장 때 SELECT(merge) 없이 INSERT만 한다.
 */
@Entity
@Table(name = "post_tags")
@EntityListeners(AuditingEntityListener.class)
public class PostTag implements Persistable<PostTagId> {

    @EmbeddedId
    private PostTagId id;

    @MapsId("postId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Post post;

    @MapsId("tagId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id")
    private Tag tag;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PostTag() {
    }

    public PostTag(Post post, Tag tag) {
        this.id = new PostTagId(post.getId(), tag.getId());
        this.post = post;
        this.tag = tag;
    }

    @Override
    public PostTagId getId() {
        return id;
    }

    /** 저장 전(생성 시각 없음)이면 새 행. */
    @Override
    public boolean isNew() {
        return createdAt == null;
    }

    public Post getPost() {
        return post;
    }

    public Tag getTag() {
        return tag;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
