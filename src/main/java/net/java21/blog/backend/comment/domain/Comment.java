package net.java21.blog.backend.comment.domain;

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

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.crypto.EncryptedStringConverter;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 댓글(comments, T194, FR-027~029). 답글은 한 단계까지다: {@code parent}가 NULL이면 댓글, 값이 있으면 답글이고
 * 답글의 부모는 같은 글의 최상위 댓글이어야 한다(서비스가 검사, {@code REPLY_DEPTH_EXCEEDED}).
 * 내용은 HTML을 받지 않는 일반 텍스트이며 출력할 때 이스케이프한다(research R8·보안 1).
 * 004: 비회원 댓글은 {@code user}가 NULL이고 이름·비밀번호 해시가 필수이며 작성 IP는 AES-256-GCM 암호문으로 저장한다
 * ({@code ck_comments_author}를 {@link Check}로도 적어 H2 테스트에서도 같은 제약이 걸린다, research B2·B6). 비밀 댓글은 {@code secret}.
 */
@Entity
@Table(name = "comments")
@Check(name = "ck_comments_author", constraints = "(user_id IS NOT NULL AND guest_name IS NULL)"
        + " OR (user_id IS NULL AND guest_name IS NOT NULL AND guest_password_hash IS NOT NULL)")
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

    /** 비회원 이름(004 FR-066). 회원 댓글이면 NULL. */
    @Column(name = "guest_name", length = 30)
    private String guestName;

    /** 비회원 비밀번호 BCrypt(004 FR-066). 회원 댓글이면 NULL. */
    @Column(name = "guest_password_hash", length = 100)
    private String guestPasswordHash;

    /** 비회원 작성 IP(001 FR-134 암호화). 보관 기간이 지나면 개인정보 파기 작업이 NULL로 지운다. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "guest_ip_enc", length = 128)
    private String guestIp;

    /** 비밀 댓글(004 FR-065). */
    @Column(nullable = false)
    private boolean secret;

    protected Comment() {
    }

    public Comment(Post post, User user, Comment parent, String content) {
        this.post = post;
        this.user = user;
        this.parent = parent;
        this.content = content;
    }

    /** 비회원 댓글(004 FR-066). 이름·비밀번호 해시는 {@code GuestAuthorService}가 검증·해시한 값이다. */
    public static Comment byGuest(Post post, Comment parent, String guestName, String guestPasswordHash, String guestIp,
            String content, boolean secret) {
        Comment comment = new Comment(post, null, parent, content);
        comment.guestName = guestName;
        comment.guestPasswordHash = guestPasswordHash;
        comment.guestIp = guestIp;
        comment.secret = secret;
        return comment;
    }

    /** 비밀 댓글 여부를 바꾼다(004 FR-065). */
    public void changeSecret(boolean secret) {
        this.secret = secret;
    }

    public boolean isGuest() {
        return user == null;
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

    public String getGuestName() {
        return guestName;
    }

    public String getGuestPasswordHash() {
        return guestPasswordHash;
    }

    public String getGuestIp() {
        return guestIp;
    }

    public boolean isSecret() {
        return secret;
    }
}
