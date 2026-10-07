package net.java21.blog.backend.external.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
import jakarta.persistence.UniqueConstraint;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.topic.domain.Topic;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 수집한 외부 글(external_posts, 007 data-model). 메타데이터만 저장하고 본문은 없다(SC-021). 같은 글은 블로그 안에서 {@code guid_hash},
 * 없으면 {@code link_hash}로 찾는다(FR-115). REMOVED는 되돌리지 않는다.
 */
@Entity
@Table(name = "external_posts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_external_posts_blog_guid_hash", columnNames = {"external_blog_id", "guid_hash"}),
        @UniqueConstraint(name = "uk_external_posts_blog_link_hash", columnNames = {"external_blog_id", "link_hash"})})
public class ExternalPost extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "external_blog_id", nullable = false)
    private ExternalBlog externalBlog;

    @Column(length = 1000)
    private String guid;

    @Column(name = "guid_hash", length = 64, columnDefinition = "char(64)")
    private String guidHash;

    @Column(nullable = false, length = 2000)
    private String link;

    @Column(name = "link_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String linkHash;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 200)
    private String summary;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "thumbnail_key", length = 22, columnDefinition = "char(22)")
    private String thumbnailKey;

    @Column(name = "published_at")
    private Instant publishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feed_terms_json")
    private List<String> feedTerms;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private Topic topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic_source", nullable = false, length = 10)
    private TopicSource topicSource;

    @Column(name = "topic_decided_at")
    private Instant topicDecidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classifier_topic_id")
    private Topic classifierTopic;

    @Column(name = "classifier_confidence", precision = 4, scale = 3)
    private BigDecimal classifierConfidence;

    @Column(name = "classifier_version", length = 30)
    private String classifierVersion;

    @Column(name = "click_count", nullable = false)
    private int clickCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExternalPostStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "removed_reason", length = 20)
    private RemovedReason removedReason;

    @Column(name = "link_checked_at")
    private Instant linkCheckedAt;

    protected ExternalPost() {
    }

    /**
     * 새 글. {@code publishedAt}이 null이면 처음 수집한 시각.
     */
    public ExternalPost(ExternalBlog externalBlog, FeedItem item, String guidHash, String linkHash, Topic topic,
            TopicSource topicSource, Instant now) {
        this.externalBlog = externalBlog;
        this.guid = item.guid();
        this.guidHash = guidHash;
        this.link = item.link();
        this.linkHash = linkHash;
        this.title = item.title();
        this.summary = item.summary();
        this.imageUrl = item.imageUrl();
        this.publishedAt = item.publishedAt() != null ? item.publishedAt() : now;
        this.feedTerms = List.copyOf(item.categories());
        this.topic = topic;
        this.topicSource = topicSource;
        this.status = ExternalPostStatus.ACTIVE;
    }

    /**
     * 같은 글의 갱신(research E5): 제목·요약·이미지 주소·발행 시각·카테고리가 바뀌었을 때만. 주제는 다시 정하지 않는다. guid가 바뀌었으면
     * 새 값으로. 링크가 바뀌었고 {@code linkHash}가 null이 아니면 링크와 해시를 바꾼다(null이면 다른 글이 쓰는 링크라 그대로 둠).
     *
     * @return 바뀐 것이 있으면 true
     */
    public boolean refresh(FeedItem item, String guidHash, String linkHash) {
        boolean changed = false;
        if (!Objects.equals(title, item.title())) {
            title = item.title();
            changed = true;
        }
        if (!Objects.equals(summary, item.summary())) {
            summary = item.summary();
            changed = true;
        }
        if (!Objects.equals(imageUrl, item.imageUrl())) {
            imageUrl = item.imageUrl();
            thumbnailKey = null;
            changed = true;
        }
        if (item.publishedAt() != null && !Objects.equals(publishedAt, item.publishedAt())) {
            publishedAt = item.publishedAt();
            changed = true;
        }
        List<String> terms = List.copyOf(item.categories());
        if (!Objects.equals(feedTerms == null ? List.of() : feedTerms, terms)) {
            feedTerms = terms;
            changed = true;
        }
        if (guidHash != null && !Objects.equals(this.guidHash, guidHash)) {
            this.guid = item.guid();
            this.guidHash = guidHash;
            changed = true;
        }
        if (linkHash != null && !Objects.equals(link, item.link())) {
            link = item.link();
            this.linkHash = linkHash;
            changed = true;
        }
        return changed;
    }

    /** 분류기 결과(채택 여부와 관계없이, FR-122). */
    public void recordClassifier(Topic topic, double confidence, String version) {
        this.classifierTopic = topic;
        this.classifierConfidence = BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP);
        this.classifierVersion = version;
    }

    /** 주제 변경(주인·검수). 사람이 정한 시각을 남긴다. */
    public void changeTopic(Topic topic, TopicSource source, Instant now) {
        this.topic = topic;
        this.topicSource = source;
        if (source.isHuman()) {
            this.topicDecidedAt = now;
        }
    }

    /**
     * 포털에서 내림(되돌리지 않음). 이미 내린 글이면 아무것도 바꾸지 않는다(오류 여부는 호출한 쪽이 정함).
     *
     * @return 이번에 내렸으면 true
     */
    public boolean remove(RemovedReason reason) {
        if (status == ExternalPostStatus.REMOVED) {
            return false;
        }
        this.status = ExternalPostStatus.REMOVED;
        this.removedReason = reason;
        return true;
    }

    public boolean isActive() {
        return status == ExternalPostStatus.ACTIVE;
    }

    public void attachThumbnail(String key) {
        this.thumbnailKey = key;
    }

    public void linkChecked(Instant now) {
        this.linkCheckedAt = now;
    }

    public Long getId() {
        return id;
    }

    public ExternalBlog getExternalBlog() {
        return externalBlog;
    }

    public String getGuid() {
        return guid;
    }

    public String getGuidHash() {
        return guidHash;
    }

    public String getLink() {
        return link;
    }

    public String getLinkHash() {
        return linkHash;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getThumbnailKey() {
        return thumbnailKey;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public List<String> getFeedTerms() {
        return feedTerms == null ? List.of() : feedTerms;
    }

    public Topic getTopic() {
        return topic;
    }

    public TopicSource getTopicSource() {
        return topicSource;
    }

    public Instant getTopicDecidedAt() {
        return topicDecidedAt;
    }

    public Topic getClassifierTopic() {
        return classifierTopic;
    }

    public BigDecimal getClassifierConfidence() {
        return classifierConfidence;
    }

    public String getClassifierVersion() {
        return classifierVersion;
    }

    public int getClickCount() {
        return clickCount;
    }

    public ExternalPostStatus getStatus() {
        return status;
    }

    public RemovedReason getRemovedReason() {
        return removedReason;
    }

    public Instant getLinkCheckedAt() {
        return linkCheckedAt;
    }
}
