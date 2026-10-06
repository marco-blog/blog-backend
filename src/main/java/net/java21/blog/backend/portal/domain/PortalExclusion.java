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
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.user.domain.User;

/**
 * 포털 제외(portal_exclusions, 003 FR-093). 글 하나에 행 하나({@code uk_portal_exclusions_post}). 포털에만 영향이 있고
 * 블로그·검색·RSS 노출은 그대로다. 해제는 행 삭제. 007의 외부 글 컬럼({@code external_post_id})은 매핑하지 않는다
 * (NULL 허용, CHECK는 {@code post_id}만 채우면 만족).
 */
@Entity
@Table(name = "portal_exclusions")
public class PortalExclusion extends BaseTimeEntity {

    public static final int REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, unique = true, updatable = false)
    private Post post;

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

    public String getReason() {
        return reason;
    }

    public User getExcludedBy() {
        return excludedBy;
    }
}
