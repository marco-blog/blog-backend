package net.java21.blog.backend.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.Check;

/**
 * 포털 제외(portal_exclusions, 003 FR-093, 007 FR-123). 내부 글({@code post_id}) 또는 외부 글({@code external_post_id}) 중 정확히
 * 하나에 행 하나({@code uk_portal_exclusions_post}·{@code uk_portal_exclusions_external_post}, {@code ck_portal_exclusions_target}).
 * 포털에만 영향이 있고 블로그·검색·RSS 노출은 그대로다. 해제는 행 삭제.
 */
@Entity
@Table(name = "portal_exclusions")
@Check(name = "ck_portal_exclusions_target", constraints = "(post_id IS NULL) <> (external_post_id IS NULL)")
public class PortalExclusion extends BaseTimeEntity {

    public static final int REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", unique = true, updatable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "external_post_id", unique = true, updatable = false)
    private ExternalPost externalPost;

    @Column(nullable = false, length = REASON_MAX)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "excluded_by", nullable = false)
    private User excludedBy;

    protected PortalExclusion() {
    }

    public PortalExclusion(Post post, String reason, User excludedBy) {
        this.post = post;
        this.reason = reason;
        this.excludedBy = excludedBy;
    }

    /** 외부 글 제외(007). */
    public PortalExclusion(ExternalPost externalPost, String reason, User excludedBy) {
        this.externalPost = externalPost;
        this.reason = reason;
        this.excludedBy = excludedBy;
    }

    /** 사유를 바꾼다(PUT 멱등, 처리한 관리자도 바뀐 사람으로). */
    public void changeReason(String reason, User excludedBy) {
        this.reason = reason;
        this.excludedBy = excludedBy;
    }

    public Long getId() {
        return id;
    }

    public Post getPost() {
        return post;
    }

    public ExternalPost getExternalPost() {
        return externalPost;
    }

    public String getReason() {
        return reason;
    }

    public User getExcludedBy() {
        return excludedBy;
    }
}
