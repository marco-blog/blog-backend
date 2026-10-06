package net.java21.blog.backend.notification.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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

import com.querydsl.core.annotations.PropertyType;
import com.querydsl.core.annotations.QueryType;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 알림(notifications, 002 FR-033, data-model). 회원 단위 하나의 목록이며 내 여러 블로그의 알림이 함께 모인다.
 * 문구는 저장하지 않고 {@code type}과 {@code params_json}(만들 때의 값, 번역하지 않음)으로 front가 화면 언어로 만든다.
 * {@code target_id}는 외래 키가 없어 대상이 지워져도 알림은 남는다. 90일이 지나면 정리 작업이 지운다.
 */
@Entity
@Table(name = "notifications")
public class Notification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 받는 회원. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 알림을 일으킨 회원. 비회원·시스템이면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actor;

    /** 관련된 내 블로그. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blog_id")
    private Blog blog;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", length = 20)
    private NotificationTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_json")
    @QueryType(PropertyType.SIMPLE)
    private Map<String, Object> params = new LinkedHashMap<>();

    /** 읽은 시각. null이면 안 읽음. */
    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    public Notification(User user, User actor, Blog blog, NotificationType type, NotificationTargetType targetType,
            Long targetId, Map<String, Object> params) {
        this.user = user;
        this.actor = actor;
        this.blog = blog;
        this.type = type;
        this.targetType = targetType;
        this.targetId = targetId;
        this.params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public User getActor() {
        return actor;
    }

    public Blog getBlog() {
        return blog;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationTargetType getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
