package net.java21.blog.backend.admin.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.crypto.EncryptedStringConverter;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 관리자 작업 기록(admin_audit_logs, T160, 006 FR-106). 관리자·시각·대상·변경 전후 값을 남기며 한번 쓰면 바꾸거나 지우지 않는다
 * ({@link Immutable}, 수정 메서드 없음, 리포지토리에도 삭제가 없다. 1년 뒤 정리는 006의 정리 작업이 한다).
 * 변경 전후 값은 바뀐 필드만 담고 개인정보 평문을 넣지 않는다. 요청 IP는 AES-256-GCM 암호문으로 저장한다.
 */
@Entity
@Immutable
@Table(name = "admin_audit_logs")
@EntityListeners(AuditingEntityListener.class)
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_id", nullable = false, updatable = false)
    private User admin;

    @Column(nullable = false, length = 50, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, length = 30, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(name = "target_key", length = 100, updatable = false)
    private String targetKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", updatable = false)
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", updatable = false)
    private Map<String, Object> after;

    @Column(length = 500, updatable = false)
    private String reason;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "request_ip_enc", length = 128, updatable = false)
    private String requestIp;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminAuditLog() {
    }

    /**
     * @param admin     작업한 관리자
     * @param action    작업 종류(예: {@code USER_BLOG_LIMIT_CHANGE})
     * @param targetType 대상 종류(예: {@code USER})
     * @param targetId  대상 숫자 ID(없으면 null)
     * @param targetKey 숫자 ID가 아닌 대상 식별 값(없으면 null)
     * @param before    변경 전 값(바뀐 필드만)
     * @param after     변경 후 값(바뀐 필드만)
     * @param reason    관리자가 입력한 사유(없으면 null)
     * @param requestIp 요청 IP(암호화해 저장)
     */
    public AdminAuditLog(User admin, String action, String targetType, Long targetId, String targetKey,
            Map<String, Object> before, Map<String, Object> after, String reason, String requestIp) {
        this.admin = admin;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetKey = targetKey;
        this.before = before == null ? null : new LinkedHashMap<>(before);
        this.after = after == null ? null : new LinkedHashMap<>(after);
        this.reason = reason;
        this.requestIp = requestIp;
    }

    public Long getId() {
        return id;
    }

    public User getAdmin() {
        return admin;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public Map<String, Object> getBefore() {
        return before;
    }

    public Map<String, Object> getAfter() {
        return after;
    }

    public String getReason() {
        return reason;
    }

    public String getRequestIp() {
        return requestIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
