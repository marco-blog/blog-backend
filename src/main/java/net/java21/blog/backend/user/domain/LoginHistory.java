package net.java21.blog.backend.user.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.crypto.EncryptedStringConverter;

/**
 * 로그인 기록(login_history, FR-139). 로그인 성공·실패마다 한 행을 남기고 90일 뒤 파기한다({@code PrivacyPurgeJob}).
 * <ul>
 *   <li>{@code user}: 없는 이메일로 시도했으면 null.</li>
 *   <li>{@code ip}: 암호화해 {@code ip_enc}(VARBINARY(128))에 저장한다(FR-134). 화면에는 일부를 가려 보여준다.</li>
 *   <li>{@code updated_at} 컬럼이 없는 추가 전용 테이블이라 {@code BaseTimeEntity}를 상속하지 않고 시각을 직접 넣는다.</li>
 * </ul>
 */
@Entity
@Table(name = "login_history")
public class LoginHistory {

    public static final int USER_AGENT_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private boolean success;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "ip_enc", length = 128)
    private String ip;

    @Column(name = "user_agent", length = USER_AGENT_MAX)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LoginHistory() {
    }

    public LoginHistory(User user, boolean success, String ip, String userAgent, Instant createdAt) {
        this.user = user;
        this.success = success;
        this.ip = ip;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
