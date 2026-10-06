package net.java21.blog.backend.like.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 좋아요(post_likes, 002 FR-030). 회원·글 쌍마다 하나다. 쓰기는 엔티티 저장 대신 {@code INSERT IGNORE}와 원자적 카운터 UPDATE로 한다
 * ({@code PostLikeRepository}, research D1). 글을 영구 삭제하면 DB의 {@code ON DELETE CASCADE}로 함께 지워지고, 회원 탈퇴 후에도 남는다.
 */
@Entity
@Table(name = "post_likes")
public class PostLike {

    @EmbeddedId
    private PostLikeId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @MapsId("postId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Post post;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PostLike() {
    }

    public PostLikeId getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Post getPost() {
        return post;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
