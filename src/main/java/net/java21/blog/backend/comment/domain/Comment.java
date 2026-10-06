package net.java21.blog.backend.comment.domain;

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
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 댓글(comments, T194, FR-027~029). 답글은 한 단계까지다: {@code parent}가 NULL이면 댓글, 값이 있으면 답글이고
 * 답글의 부모는 같은 글의 최상위 댓글이어야 한다(서비스가 검사, {@code REPLY_DEPTH_EXCEEDED}).
 * 내용은 HTML을 받지 않는 일반 텍스트이며 출력할 때 이스케이프한다(research R8·보안 1).
 * 004(비회원 {@code guest_*}·비밀 {@code secret}) 컬럼은 DB 기본값/NULL이 있으므로 매핑하지 않는다.
 */
@Entity
@Table(name = "comments")
public class Comment extends BaseTimeEntity {

    public static final int CONTENT_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    /** 작성 회원. 004의 비회원 댓글은 NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** NULL=댓글, 값=답글(1단계). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Comment parent;

    @Column(nullable = false, length = CONTENT_MAX)
    private String content;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private CommentStatus status = CommentStatus.ACTIVE;

    protected Comment() {
    }

    public Comment(Post post, User user, Comment parent, String content) {
        this.post = post;
        this.user = user;
        this.parent = parent;
        this.content = content;
    }

    public boolean isReply() {
        return parent != null;
    }

    public boolean isDeleted() {
        return status == CommentStatus.DELETED;
    }

    /** 작성자인지. 회원 id만 보므로 작성자 프록시를 초기화하지 않는다. */
    public boolean isWrittenBy(Long userId) {
        return userId != null && user != null && userId.equals(user.getId());
    }

    public void edit(String content) {
        this.content = content;
    }

    /** 답글이 남은 최상위 댓글을 "삭제된 댓글" 자리로 남긴다. */
    public void markDeleted() {
        this.status = CommentStatus.DELETED;
    }

    public Long getId() {
        return id;
    }

    public Post getPost() {
        return post;
    }

    public User getUser() {
        return user;
    }

    public Comment getParent() {
        return parent;
    }

    public String getContent() {
        return content;
    }

    public CommentStatus getStatus() {
        return status;
    }
}
