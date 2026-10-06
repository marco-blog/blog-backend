package net.java21.blog.backend.user.domain;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.crypto.EncryptedStringConverter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 회원(users). 이메일은 정규화(소문자) 후 암호화해 {@code email_enc}에, 검색용 HMAC은 {@code email_hash}에 둔다(FR-134·135).
 * 002~007이 더한 컬럼(last_seen_release_version 등)은 DB 기본값이 있으므로 매핑하지 않는다.
 * 열거형은 MySQL에서 VARCHAR이므로 {@code @JdbcTypeCode(VARCHAR)}로 Hibernate의 ENUM 타입 추론을 막는다(ddl-auto=validate).
 */
@Entity
@Table(name = "users")
public class User extends BaseTimeEntity {

    public static final String DEFAULT_TIME_ZONE = "Asia/Seoul";
    public static final int NICKNAME_MAX = 30;
    public static final int BIO_MAX = 300;
    /** 개인정보 파기 후의 닉네임(FR-138). */
    public static final String PURGED_NICKNAME = "withdrawn";
    /**
     * 개인정보 파기를 마쳤다는 표시. BCrypt 형식이 아니라 어떤 비밀번호와도 맞지 않는다.
     * 스키마를 바꾸지 않고 파기 작업이 같은 회원을 다시 처리하지 않게 한다.
     */
    public static final String PURGED_PASSWORD_HASH = "PURGED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 프로필 이미지(media). 미디어 엔티티는 US4에서 연결한다. */
    @Column(name = "profile_media_id")
    private Long profileMediaId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email_enc", nullable = false, length = 512)
    private String email;

    @Column(name = "email_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    private String emailHash;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(length = 300)
    private String bio;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 15)
    private UserRole role = UserRole.USER;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private UserStatus status = UserStatus.ACTIVE;

    /** ko / en / ja / zh-CN, null=미설정. */
    @Column(length = 10)
    private String locale;

    @Column(name = "time_zone", nullable = false, length = 40)
    private String timeZone = DEFAULT_TIME_ZONE;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "terms_version", nullable = false, length = 20)
    private String termsVersion;

    @Column(name = "terms_agreed_at", nullable = false)
    private Instant termsAgreedAt;

    /** 회원별 블로그 한도. null이면 {@code blog.blogs.default-max-per-member}. */
    @Column(name = "max_blogs")
    private Integer maxBlogs;

    protected User() {
    }

    public User(String normalizedEmail, String emailHash, String passwordHash, String nickname,
            String locale, String timeZone, String termsVersion, Instant termsAgreedAt) {
        this.email = normalizedEmail;
        this.emailHash = emailHash;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.locale = locale;
        this.timeZone = timeZone == null ? DEFAULT_TIME_ZONE : timeZone;
        this.termsVersion = termsVersion;
        this.termsAgreedAt = termsAgreedAt;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * 로그인 실패를 센다. {@code maxFailures}번 연속이면 {@code lockDuration} 동안 잠그고 횟수를 0으로 되돌린다.
     *
     * @return 이번 실패로 잠겼으면 true
     */
    public boolean recordLoginFailure(int maxFailures, Duration lockDuration, Instant now) {
        failedLoginCount++;
        if (failedLoginCount >= maxFailures) {
            failedLoginCount = 0;
            lockedUntil = now.plus(lockDuration);
            return true;
        }
        return false;
    }

    public void recordLoginSuccess() {
        failedLoginCount = 0;
        lockedUntil = null;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changeBio(String bio) {
        this.bio = bio;
    }

    /** ko / en / ja / zh-CN, null=미설정(FR-149). */
    public void changeLocale(String locale) {
        this.locale = locale;
    }

    /** IANA 시간대 ID(FR-153). */
    public void changeTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    /** 비밀번호 변경·재설정. 재설정은 잠금도 풀어 준다(새 비밀번호로 바로 로그인할 수 있게). */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    /** 탈퇴(FR-009). 되돌릴 수 없다. 글 비공개·토큰 폐기는 서비스가 같은 트랜잭션에서 한다. */
    public void withdraw(Instant now) {
        this.status = UserStatus.WITHDRAWN;
        this.withdrawnAt = now;
    }

    /**
     * 탈퇴 보존 기간 뒤 개인정보 파기(FR-138, tasks.md "구현 전 결정 사항" 10번). NOT NULL·UNIQUE를 지키도록
     * 이메일 자리에는 {@code withdrawn:{id}}(암호화), 해시 자리에는 그 HMAC을 넣는다.
     */
    public void purgePersonalData(String anonymousEmail, String anonymousEmailHash) {
        this.email = anonymousEmail;
        this.emailHash = anonymousEmailHash;
        this.nickname = PURGED_NICKNAME;
        this.bio = null;
        this.profileMediaId = null;
        this.passwordHash = PURGED_PASSWORD_HASH;
    }

    /** 실제 적용되는 블로그 한도. */
    public int effectiveBlogLimit(int defaultMax) {
        return maxBlogs == null ? defaultMax : maxBlogs;
    }

    public Long getId() {
        return id;
    }

    public Long getProfileMediaId() {
        return profileMediaId;
    }

    public String getEmail() {
        return email;
    }

    public String getEmailHash() {
        return emailHash;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public String getBio() {
        return bio;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public String getLocale() {
        return locale;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public Instant getTermsAgreedAt() {
        return termsAgreedAt;
    }

    public Integer getMaxBlogs() {
        return maxBlogs;
    }
}
