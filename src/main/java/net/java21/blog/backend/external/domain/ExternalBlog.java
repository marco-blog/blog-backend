package net.java21.blog.backend.external.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.feed.FeedFormat;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;

/**
 * 외부 블로그 등록과 수집 상태(external_blogs, 007 data-model). 상태 전이는 이 클래스의 메서드로만 하고, 지금 상태에서 할 수 없는 전이는
 * 409 {@code EXTERNAL_BLOG_STATE_CONFLICT}({@code params: { status, action }}). {@code active_feed_hash}는 MySQL 생성 컬럼이라 쓰지
 * 않는다(거절·해제가 아닌 등록은 피드당 하나, FR-112).
 */
@Entity
@Table(name = "external_blogs")
public class ExternalBlog extends BaseTimeEntity {

    public static final int TITLE_MAX = 200;
    public static final int URL_MAX = 1000;
    public static final int REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_type", nullable = false, length = 15)
    private RegistrationType registrationType;

    /** 신청·소유 회원(관리 권한자). 운영자 직접 등록이고 아무도 넘겨받지 않았으면 NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private User member;

    @Column(name = "ownership_verified", nullable = false)
    private boolean ownershipVerified;

    @Column(name = "ownership_verified_at")
    private Instant ownershipVerifiedAt;

    @Column(name = "registration_basis", length = REASON_MAX)
    private String registrationBasis;

    @Column(length = TITLE_MAX)
    private String title;

    @Column(name = "site_url", length = URL_MAX)
    private String siteUrl;

    @Column(name = "feed_url", nullable = false, length = URL_MAX)
    private String feedUrl;

    @Column(name = "feed_url_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String feedUrlHash;

    /** MySQL 생성 컬럼(읽기 전용). */
    @Column(name = "active_feed_hash", insertable = false, updatable = false, unique = true, length = 64,
            columnDefinition = "char(64)")
    private String activeFeedHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "feed_format", length = 10)
    private FeedFormat feedFormat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "default_topic_id", nullable = false)
    private Topic defaultTopic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExternalBlogStatus status;

    @Column(name = "reject_reason", length = REASON_MAX)
    private String rejectReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(length = 255)
    private String etag;

    @Column(name = "last_modified", length = 64)
    private String lastModified;

    @Column(name = "next_fetch_at")
    private Instant nextFetchAt;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_fetch_result", length = 20)
    private FetchResultCode lastFetchResult;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "first_failed_at")
    private Instant firstFailedAt;

    protected ExternalBlog() {
    }

    private ExternalBlog(RegistrationType type, User member, String feedUrl, String feedUrlHash, Topic defaultTopic,
            ExternalBlogStatus status) {
        this.registrationType = type;
        this.member = member;
        this.feedUrl = feedUrl;
        this.feedUrlHash = feedUrlHash;
        this.defaultTopic = defaultTopic;
        this.status = status;
    }

    /** 회원 신청(PENDING). */
    public static ExternalBlog memberRequest(User member, String feedUrl, String feedUrlHash, Topic defaultTopic) {
        return new ExternalBlog(RegistrationType.MEMBER_REQUEST, member, feedUrl, feedUrlHash, defaultTopic,
                ExternalBlogStatus.PENDING);
    }

    /** 운영자 직접 등록(바로 ACTIVE, 회원 없음). */
    public static ExternalBlog adminDirect(User admin, String basis, String feedUrl, String feedUrlHash,
            Topic defaultTopic, Instant now) {
        ExternalBlog blog = new ExternalBlog(RegistrationType.ADMIN_DIRECT, null, feedUrl, feedUrlHash, defaultTopic,
                ExternalBlogStatus.ACTIVE);
        blog.registrationBasis = basis;
        blog.reviewedBy = admin;
        blog.reviewedAt = now;
        blog.nextFetchAt = now;
        return blog;
    }

    /** 피드에서 읽은 이름·주소·형식(비어 있을 때만 채움, 형식은 늘 갱신). */
    public void describe(String title, String siteUrl, FeedFormat format) {
        if ((this.title == null || this.title.isBlank()) && title != null && !title.isBlank()) {
            this.title = title.length() > TITLE_MAX ? title.substring(0, TITLE_MAX) : title;
        }
        if ((this.siteUrl == null || this.siteUrl.isBlank()) && siteUrl != null && siteUrl.length() <= URL_MAX) {
            this.siteUrl = siteUrl;
        }
        if (format != null) {
            this.feedFormat = format;
        }
    }

    /** 신청 때 쓴 소유 인증(같은 회원·같은 피드). */
    public void markVerified(Instant now) {
        this.ownershipVerified = true;
        this.ownershipVerifiedAt = now;
    }

    public void approve(User admin, Instant now) {
        require(Set.of(ExternalBlogStatus.PENDING), "approve");
        this.status = ExternalBlogStatus.ACTIVE;
        this.reviewedBy = admin;
        this.reviewedAt = now;
        this.nextFetchAt = now;
    }

    public void reject(User admin, String reason, Instant now) {
        require(Set.of(ExternalBlogStatus.PENDING), "reject");
        this.status = ExternalBlogStatus.REJECTED;
        this.rejectReason = reason;
        this.reviewedBy = admin;
        this.reviewedAt = now;
        this.nextFetchAt = null;
    }

    public void pause() {
        require(Set.of(ExternalBlogStatus.ACTIVE), "pause");
        this.status = ExternalBlogStatus.PAUSED;
        this.nextFetchAt = null;
    }

    public void resume(Instant now) {
        require(Set.of(ExternalBlogStatus.PAUSED, ExternalBlogStatus.STOPPED), "resume");
        this.status = ExternalBlogStatus.ACTIVE;
        this.consecutiveFailures = 0;
        this.firstFailedAt = null;
        this.nextFetchAt = now;
    }

    public void block() {
        if (status == ExternalBlogStatus.BLOCKED) {
            throw conflict("block", Map.of());
        }
        this.status = ExternalBlogStatus.BLOCKED;
        this.nextFetchAt = null;
    }

    public void release() {
        require(ExternalBlogStatus.RELEASABLE, "release");
        this.status = ExternalBlogStatus.RELEASED;
        this.nextFetchAt = null;
    }

    /** 연속 실패 자동 중지(FR-117). */
    public void stop() {
        require(Set.of(ExternalBlogStatus.ACTIVE), "stop");
        this.status = ExternalBlogStatus.STOPPED;
        this.nextFetchAt = null;
    }

    /** 소유 인증으로 넘겨받기(FR-129). */
    public void claim(User user, Instant now) {
        require(ExternalBlogStatus.CLAIMABLE, "claim");
        this.member = user;
        markVerified(now);
    }

    /** 해제된 등록이 아니어야 하는 동작(주제 변경 등). */
    public void requireNotReleased(String action) {
        if (status == ExternalBlogStatus.RELEASED) {
            throw conflict(action, Map.of());
        }
    }

    public void changeDefaultTopic(Topic topic) {
        this.defaultTopic = topic;
    }

    /** 수집 임대(research E1): 고른 행을 {@code until}까지 다시 고르지 않는다. */
    public void lease(Instant until) {
        this.nextFetchAt = until;
    }

    /** 수집 성공(200·304). */
    public void recordSuccess(FetchResultCode result, Integer httpStatus, String etag, String lastModified,
            Instant now, Instant next) {
        this.lastFetchedAt = now;
        this.lastSuccessAt = now;
        this.lastFetchResult = result;
        this.lastHttpStatus = httpStatus;
        if (etag != null) {
            this.etag = etag.length() > 255 ? null : etag;
        }
        if (lastModified != null) {
            this.lastModified = lastModified.length() > 64 ? null : lastModified;
        }
        this.consecutiveFailures = 0;
        this.firstFailedAt = null;
        this.nextFetchAt = status == ExternalBlogStatus.ACTIVE ? next : null;
    }

    /** 수집 실패. 실패 수를 올리고 첫 실패 시각을 남긴다. */
    public void recordFailure(FetchResultCode result, Integer httpStatus, Instant now, Instant next) {
        this.lastFetchedAt = now;
        this.lastFetchResult = result;
        this.lastHttpStatus = httpStatus;
        this.consecutiveFailures++;
        if (firstFailedAt == null) {
            this.firstFailedAt = now;
        }
        this.nextFetchAt = status == ExternalBlogStatus.ACTIVE ? next : null;
    }

    private void require(Set<ExternalBlogStatus> allowed, String action) {
        if (!allowed.contains(status)) {
            throw conflict(action, Map.of());
        }
    }

    /** 409 {@code EXTERNAL_BLOG_STATE_CONFLICT}({@code status}, {@code action}, 추가 값). */
    public BusinessException conflict(String action, Map<String, ?> extra) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", status.name());
        params.put("action", action);
        params.putAll(extra);
        return BusinessException.withParams(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT,
                "External blog is " + status + ", cannot " + action, params);
    }

    /** 알림·문구용 이름(없으면 피드 주소의 호스트). */
    public String displayTitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        try {
            String host = java.net.URI.create(feedUrl).getHost();
            return host == null ? feedUrl : host;
        } catch (IllegalArgumentException e) {
            return feedUrl;
        }
    }

    /** 사이트 호스트(카드 표시용). */
    public String siteHost() {
        String url = siteUrl != null ? siteUrl : feedUrl;
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public boolean isManagedBy(Long userId) {
        return member != null && userId != null && userId.equals(member.getId());
    }

    public Long getId() {
        return id;
    }

    public RegistrationType getRegistrationType() {
        return registrationType;
    }

    public User getMember() {
        return member;
    }

    public boolean isOwnershipVerified() {
        return ownershipVerified;
    }

    public Instant getOwnershipVerifiedAt() {
        return ownershipVerifiedAt;
    }

    public String getRegistrationBasis() {
        return registrationBasis;
    }

    public String getTitle() {
        return title;
    }

    public String getSiteUrl() {
        return siteUrl;
    }

    public String getFeedUrl() {
        return feedUrl;
    }

    public String getFeedUrlHash() {
        return feedUrlHash;
    }

    public String getActiveFeedHash() {
        return activeFeedHash;
    }

    public FeedFormat getFeedFormat() {
        return feedFormat;
    }

    public Topic getDefaultTopic() {
        return defaultTopic;
    }

    public ExternalBlogStatus getStatus() {
        return status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getEtag() {
        return etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public Instant getNextFetchAt() {
        return nextFetchAt;
    }

    public Instant getLastFetchedAt() {
        return lastFetchedAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public FetchResultCode getLastFetchResult() {
        return lastFetchResult;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getFirstFailedAt() {
        return firstFailedAt;
    }
}
