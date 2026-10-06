package net.java21.blog.backend.portal.domain;

import java.time.Instant;

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
 * 운영자 추천(portal_curations, 003 FR-091·092). 노출 기간 {@code [startsAt, endsAt)} 안이고 글이 포털 노출 조건을 만족할 때만
 * 메인 추천 영역에 {@code sortOrder} 순으로 나온다. 조건을 잃은 글의 행은 지우지 않는다(화면에서만 빠짐).
 */
@Entity
@Table(name = "portal_curations")
public class PortalCuration extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    protected PortalCuration() {
    }

    public PortalCuration(Post post, Instant startsAt, Instant endsAt, int sortOrder, User createdBy) {
        this.post = post;
        this.createdBy = createdBy;
        reschedule(startsAt, endsAt, sortOrder);
    }

    /** 기간·순서를 바꾼다. 종료는 시작보다 늦어야 한다(CHECK ends_at > starts_at). */
    public void reschedule(Instant startsAt, Instant endsAt, int sortOrder) {
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Curation must end after it starts");
        }
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.sortOrder = sortOrder;
    }

    /** {@code now}가 노출 기간 안인지(시작 포함, 종료 제외). */
    public boolean isActiveAt(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    public Long getId() {
        return id;
    }

    public Post getPost() {
        return post;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public User getCreatedBy() {
        return createdBy;
    }
}
