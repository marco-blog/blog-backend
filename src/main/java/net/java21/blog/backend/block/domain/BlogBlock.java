package net.java21.blog.backend.block.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.user.domain.User;

/**
 * 블로그별 회원 차단(blog_blocks, 004 FR-146, research B13). 차단된 회원은 그 블로그에 댓글·방명록을 쓰거나 구독할 수 없다.
 * {@code updated_at} 컬럼이 없는 추가·삭제 전용 테이블이라 {@code BaseTimeEntity}를 상속하지 않고 시각을 직접 넣는다.
 */
@Entity
@Table(name = "blog_blocks")
public class BlogBlock {

    @EmbeddedId
    private BlogBlockId id;

    @MapsId("blogId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blog_id")
    private Blog blog;

    @MapsId("blockedUserId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_user_id")
    private User blockedUser;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BlogBlock() {
    }

    public BlogBlock(Blog blog, User blockedUser, Instant createdAt) {
        this.blog = blog;
        this.blockedUser = blockedUser;
        this.id = new BlogBlockId(blog.getId(), blockedUser.getId());
        this.createdAt = createdAt;
    }

    public BlogBlockId getId() {
        return id;
    }

    public Blog getBlog() {
        return blog;
    }

    public User getBlockedUser() {
        return blockedUser;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
