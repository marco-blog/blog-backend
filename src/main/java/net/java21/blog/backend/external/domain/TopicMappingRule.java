package net.java21.blog.backend.external.domain;

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
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;

/** 주제 매핑 규칙(topic_mapping_rules, 007 FR-118·121). {@code keyword}는 정규화(NFKC·trim·소문자)한 값이고 유일하다. */
@Entity
@Table(name = "topic_mapping_rules")
public class TopicMappingRule extends BaseTimeEntity {

    public static final int KEYWORD_MAX = 100;
    public static final int PRIORITY_MIN = -1000;
    public static final int PRIORITY_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = KEYWORD_MAX)
    private String keyword;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private Topic topic;

    @Column(nullable = false)
    private int priority;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    protected TopicMappingRule() {
    }

    public TopicMappingRule(String keyword, Topic topic, int priority, User createdBy) {
        this.keyword = keyword;
        this.topic = topic;
        this.priority = priority;
        this.createdBy = createdBy;
    }

    public void change(String keyword, Topic topic, Integer priority) {
        if (keyword != null) {
            this.keyword = keyword;
        }
        if (topic != null) {
            this.topic = topic;
        }
        if (priority != null) {
            this.priority = priority;
        }
    }

    public Long getId() {
        return id;
    }

    public String getKeyword() {
        return keyword;
    }

    public Topic getTopic() {
        return topic;
    }

    public int getPriority() {
        return priority;
    }

    public User getCreatedBy() {
        return createdBy;
    }
}
