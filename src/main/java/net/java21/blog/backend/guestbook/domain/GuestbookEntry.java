package net.java21.blog.backend.guestbook.domain;

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

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.crypto.EncryptedStringConverter;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 방명록 글(guestbook_entries, 004 FR-056~058, FR-066). {@code parent}가 NULL이면 방명록 글, 값이 있으면 블로그 주인의 답글이며
 * 답글의 부모는 최상위 글이어야 한다(1단계, 서비스가 검사). 회원 글은 {@code user}, 비회원 글은 이름·비밀번호 해시가 필수이고
 * 작성 IP는 AES-256-GCM 암호문으로 저장한다. 스키마의 {@code ck_guestbook_entries_author}를 {@link Check}로도 적는다(research B2).
 * 내용은 HTML을 받지 않는 일반 텍스트이며 출력할 때 이스케이프한다.
 */
@Entity
@Table(name = "guestbook_entries")
@Check(name = "ck_guestbook_entries_author", constraints = "(user_id IS NOT NULL AND guest_name IS NULL)"
        + " OR (user_id IS NULL AND guest_name IS NOT NULL AND guest_password_hash IS NOT NULL)")
public class GuestbookEntry extends BaseTimeEntity {

    public static final int CONTENT_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blog_id", nullable = false)
    private Blog blog;

    /** 작성 회원. 비회원 글이면 NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** NULL=방명록 글, 값=블로그 주인의 답글(1단계). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private GuestbookEntry parent;

    @Column(name = "guest_name", length = 30)
    private String guestName;

    @Column(name = "guest_password_hash", length = 100)
    private String guestPasswordHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "guest_ip_enc", length = 128)
    private String guestIp;

    @Column(nullable = false, length = CONTENT_MAX)
    private String content;

    /** 비밀글(FR-057): 블로그 주인과 작성자만 내용을 본다. */
    @Column(nullable = false)
    private boolean secret;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private GuestbookStatus status = GuestbookStatus.ACTIVE;

    protected GuestbookEntry() {
    }

    /** 회원 글(답글이면 {@code parent}). */
    public GuestbookEntry(Blog blog, User user, GuestbookEntry parent, String content, boolean secret) {
        this.blog = blog;
        this.user = user;
        this.parent = parent;
        this.content = content;
        this.secret = secret;
    }

    /** 비회원 글. 이름·비밀번호 해시는 {@code GuestAuthorService}가 검증·해시한 값이다. */
    public static GuestbookEntry byGuest(Blog blog, String guestName, String guestPasswordHash, String guestIp,
            String content, boolean secret) {
        GuestbookEntry entry = new GuestbookEntry(blog, null, null, content, secret);
        entry.guestName = guestName;
        entry.guestPasswordHash = guestPasswordHash;
        entry.guestIp = guestIp;
        return entry;
    }

    public boolean isReply() {
        return parent != null;
    }

    public boolean isGuest() {
        return user == null;
    }

    public boolean isDeleted() {
        return status == GuestbookStatus.DELETED;
    }

    /** 작성 회원인지. 회원 id만 보므로 작성자 프록시를 초기화하지 않는다. */
    public boolean isOwnedBy(Long userId) {
        return userId != null && user != null && userId.equals(user.getId());
    }

    /** 내용·비밀 여부를 고친다. {@code secret}이 null이면 그대로 둔다. */
    public void edit(String content, Boolean secret) {
        if (content != null) {
            this.content = content;
        }
        if (secret != null) {
            this.secret = secret;
        }
    }

    /** 답글이 남은 글을 "삭제된 글" 자리로 남긴다. */
    public void markDeleted() {
        this.status = GuestbookStatus.DELETED;
    }

    public Long getId() {
        return id;
    }

    public Blog getBlog() {
        return blog;
    }

    public User getUser() {
        return user;
    }

    /** 작성 회원 id(프록시를 초기화하지 않는다). 비회원이면 null. */
    public Long getUserId() {
        return user == null ? null : user.getId();
    }

    public GuestbookEntry getParent() {
        return parent;
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

    public String getContent() {
        return content;
    }

    public boolean isSecret() {
        return secret;
    }

    public GuestbookStatus getStatus() {
        return status;
    }
}
