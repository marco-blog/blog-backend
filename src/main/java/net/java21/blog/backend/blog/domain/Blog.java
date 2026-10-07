package net.java21.blog.backend.blog.domain;

import java.time.Instant;
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
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 블로그(blogs). 회원 1 : 블로그 N(R28). {@code handle}은 삭제된 블로그를 포함해 유일하고 바꿀 수 없다(FR-002, FR-159).
 * 002의 구독자 수({@code subscriber_count}, 읽기 전용 카운터: 구독·취소 때 원자적 UPDATE로만 바꾼다)와 피드 설정
 * ({@code feed_item_count}, {@code feed_content_mode})과 003의 포털 설정({@code portal_enabled}, {@code default_topic_id},
 * {@code first_published_at})을 매핑한다. 004의 방명록·비회원 쓰기 설정({@code guestbook_enabled}, {@code guest_write_enabled})과
 * 전체 방문자 수({@code total_visitors}, 읽기 전용 카운터: 방문 기록의 원자적 UPDATE로만 바꾼다)를 매핑한다.
 * 005~007이 더한 컬럼({@code trackback_enabled} 등)은 DB 기본값이 있으므로 매핑하지 않는다.
 */
@Entity
@Table(name = "blogs")
public class Blog extends BaseTimeEntity {

    public static final int TITLE_MAX = 100;
    public static final int DESCRIPTION_MAX = 500;
    /** 피드에 담을 수 있는 글 수(FR-046). */
    public static final Set<Integer> FEED_ITEM_COUNTS = Set.of(10, 20, 30, 50);
    public static final int DEFAULT_FEED_ITEM_COUNT = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 대표 이미지(media, owner_type=BLOG_COVER, US4). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_media_id")
    private Media coverMedia;

    @Column(nullable = false, unique = true, length = 20, updatable = false)
    private String handle;

    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    @Column(name = "comment_enabled", nullable = false)
    private boolean commentEnabled = true;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private BlogStatus status = BlogStatus.ACTIVE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 구독자 수(FR-031). 엔티티 저장으로 바꾸지 않는다(구독·취소의 원자적 UPDATE만, 002 research D1). */
    @ColumnDefault("0")
    @Column(name = "subscriber_count", nullable = false, insertable = false, updatable = false)
    private int subscriberCount;

    /** 피드에 담을 글 수 10·20·30·50(FR-046). */
    @Column(name = "feed_item_count", nullable = false)
    private int feedItemCount = DEFAULT_FEED_ITEM_COUNT;

    /** 피드 공개 형태(FR-046). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "feed_content_mode", nullable = false, length = 10)
    private FeedContentMode feedContentMode = FeedContentMode.FULL;

    /** "포털에 내 글 노출"(003 FR-089, 기본 켜짐). 꺼도 블로그·검색·RSS에는 영향이 없다. */
    @Column(name = "portal_enabled", nullable = false)
    private boolean portalEnabled = true;

    /** 블로그 기본 주제(소분류, 003 FR-077). 새 글 작성 때 front가 미리 선택한다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_topic_id")
    private Topic defaultTopic;

    /** 처음 글을 발행한 시각(003 FR-087 "새로 시작한 블로그"). NULL일 때 한 번만 채우고 되돌리지 않는다. */
    @Column(name = "first_published_at")
    private Instant firstPublishedAt;

    /** 방명록 사용(004 FR-058, 기본 켜짐). 꺼도 행은 지우지 않는다. */
    @Column(name = "guestbook_enabled", nullable = false)
    private boolean guestbookEnabled = true;

    /** 비회원 댓글·방명록 허용(004 FR-066, 기본 꺼짐). */
    @Column(name = "guest_write_enabled", nullable = false)
    private boolean guestWriteEnabled;

    /** 전체 방문자 수(004 FR-067). 엔티티 저장으로 바꾸지 않는다(방문 기록의 원자적 UPDATE만, research B9). */
    @ColumnDefault("0")
    @Column(name = "total_visitors", nullable = false, insertable = false, updatable = false)
    private long totalVisitors;

    protected Blog() {
    }

    public Blog(User user, String handle, String title) {
        this.user = user;
        this.handle = handle;
        this.title = title;
    }

    /** 제목을 주지 않았을 때의 기본 제목. */
    public static String defaultTitle(String nickname) {
        return nickname + "의 블로그";
    }

    public boolean isActive() {
        return status == BlogStatus.ACTIVE;
    }

    public void delete(Instant now) {
        this.status = BlogStatus.DELETED;
        this.deletedAt = now;
    }

    public void changeTitle(String title) {
        this.title = title;
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void changeCommentEnabled(boolean commentEnabled) {
        this.commentEnabled = commentEnabled;
    }

    /** 피드 설정(FR-046). 글 수는 {@link #FEED_ITEM_COUNTS}만 받는다. */
    public void changeFeedSettings(int feedItemCount, FeedContentMode feedContentMode) {
        if (!FEED_ITEM_COUNTS.contains(feedItemCount)) {
            throw new IllegalArgumentException("Feed item count must be one of " + FEED_ITEM_COUNTS + ": " + feedItemCount);
        }
        if (feedContentMode == null) {
            throw new IllegalArgumentException("Feed content mode is required");
        }
        this.feedItemCount = feedItemCount;
        this.feedContentMode = feedContentMode;
    }

    /** 포털 설정(003 FR-077·089). 기본 주제 검증(소분류·숨김 아님)은 호출한 쪽이 한다. */
    public void changePortalSettings(boolean portalEnabled, Topic defaultTopic) {
        this.portalEnabled = portalEnabled;
        this.defaultTopic = defaultTopic;
    }

    /**
     * 첫 발행 시각을 남긴다(003 FR-087). 이미 값이 있으면 그대로 둔다(두 번째 글, 수정 발행, 글 삭제 후 다시 발행).
     * 004 예약 발행도 실제로 발행될 때 이 메서드를 부른다.
     */
    public void markFirstPublished(Instant publishedAt) {
        if (firstPublishedAt == null) {
            this.firstPublishedAt = publishedAt;
        }
    }

    /** 방명록·비회원 쓰기 설정(004 FR-058, FR-066). */
    public void changeGuestSettings(boolean guestbookEnabled, boolean guestWriteEnabled) {
        this.guestbookEnabled = guestbookEnabled;
        this.guestWriteEnabled = guestWriteEnabled;
    }

    public boolean isGuestbookEnabled() {
        return guestbookEnabled;
    }

    public boolean isGuestWriteEnabled() {
        return guestWriteEnabled;
    }

    public long getTotalVisitors() {
        return totalVisitors;
    }

    /** 이 회원이 주인인지. 주인 프록시를 초기화하지 않는다. */
    public boolean isOwnedBy(Long userId) {
        return userId != null && user.getId().equals(userId);
    }

    public boolean isPortalEnabled() {
        return portalEnabled;
    }

    public Topic getDefaultTopic() {
        return defaultTopic;
    }

    /** 기본 주제 id(지연 로딩 프록시를 초기화하지 않는다). */
    public Long getDefaultTopicId() {
        return defaultTopic == null ? null : defaultTopic.getId();
    }

    public Instant getFirstPublishedAt() {
        return firstPublishedAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    /** 대표 이미지를 바꾼다(null이면 지움). 이전 이미지의 정리 대상 판단은 호출한 쪽이 한다. */
    public void changeCoverMedia(Media media) {
        this.coverMedia = media;
    }

    public Media getCoverMedia() {
        return coverMedia;
    }

    /** 대표 이미지 id(지연 로딩 프록시를 초기화하지 않는다). */
    public Long getCoverMediaId() {
        return coverMedia == null ? null : coverMedia.getId();
    }

    /** 대표 이미지 주소 {@code /media/{key}} 또는 null. 이미지가 읽혀 있지 않으면 한 번 읽는다. */
    public String coverImageUrl() {
        return coverMedia == null ? null : coverMedia.url();
    }

    public String getHandle() {
        return handle;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public boolean isCommentEnabled() {
        return commentEnabled;
    }

    public BlogStatus getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public int getSubscriberCount() {
        return subscriberCount;
    }

    public int getFeedItemCount() {
        return feedItemCount;
    }

    public FeedContentMode getFeedContentMode() {
        return feedContentMode;
    }
}
