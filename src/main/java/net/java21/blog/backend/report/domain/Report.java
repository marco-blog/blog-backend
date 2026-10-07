package net.java21.blog.backend.report.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.crypto.EncryptedStringConverter;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 신고(reports, 005 FR-040·041, data-model reports). 회원 신고(MEMBER)와 비회원 권리 침해 신고(RIGHTS_REQUEST)를 한 테이블에 둔다.
 * <ul>
 *   <li>스키마의 {@code ck_reports_reporter}(MEMBER면 신고자 필수, RIGHTS_REQUEST면 NULL)와 {@code uk_reports_reporter_target}(한 회원은
 *       같은 대상을 한 번만)를 {@link Check}·{@link UniqueConstraint}로도 적어 H2 테스트에서도 같은 제약이 걸린다.</li>
 *   <li>대상은 다형 참조({@code target_type} + {@code target_id}, 외래 키 없음). 대상 작성자·블로그는 접수 시점 값이다.</li>
 *   <li>권리 침해 연락 이메일은 AES-256-GCM 암호문({@code contact_email_enc}, 001 FR-134). 처리 후 보존 기간이 지나면 파기 작업이 NULL로.</li>
 *   <li>연관은 모두 LAZY. 목록은 DTO projection으로 읽는다.</li>
 * </ul>
 */
@Entity
@Table(name = "reports", uniqueConstraints = @UniqueConstraint(name = "uk_reports_reporter_target",
        columnNames = {"reporter_id", "target_type", "target_id"}))
@Check(name = "ck_reports_reporter", constraints = "(channel = 'MEMBER') = (reporter_id IS NOT NULL)")
public class Report extends BaseTimeEntity {

    public static final int DETAIL_MAX = 1000;
    public static final int TARGET_URL_MAX = 1000;
    public static final int RIGHTS_BASIS_MAX = 2000;
    public static final int NOTE_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 15, updatable = false)
    private ReportChannel channel;

    /** 신고한 회원. 권리 침해 신고는 NULL(로그인 상태로 보내도, 결정 표 10번). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", updatable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_url", length = TARGET_URL_MAX)
    private String targetUrl;

    /** 접수 시점의 대상 작성 회원(비회원 글·외부 트랙백은 NULL). 006 FR-104 "받은 신고 수". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private User targetUser;

    /** 대상이 속한 블로그(포털 감점, 003 FR-086). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_blog_id")
    private Blog targetBlog;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, updatable = false)
    private ReportReason reason;

    @Column(length = DETAIL_MAX, updatable = false)
    private String detail;

    @Column(name = "rights_basis", length = RIGHTS_BASIS_MAX, updatable = false)
    private String rightsBasis;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_email_enc", length = 512)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private ReportStatus status = ReportStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 25)
    private ReportAction action;

    @Column(name = "resolution_note", length = NOTE_MAX)
    private String resolutionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by")
    private User handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    protected Report() {
    }

    /** 회원 신고(대상이 정해져 있음). */
    public static Report member(User reporter, ReportTargetType targetType, Long targetId, User targetUser,
            Blog targetBlog, ReportReason reason, String detail) {
        Report report = new Report();
        report.channel = ReportChannel.MEMBER;
        report.reporter = reporter;
        report.targetType = targetType;
        report.targetId = targetId;
        report.targetUser = targetUser;
        report.targetBlog = targetBlog;
        report.reason = reason;
        report.detail = detail;
        return report;
    }

    /** 권리 침해 신고. 주소를 해석하지 못했으면 대상은 NULL이고 관리자가 지정한다. */
    public static Report rightsRequest(String targetUrl, ReportReason reason, String rightsBasis, String contactEmail) {
        Report report = new Report();
        report.channel = ReportChannel.RIGHTS_REQUEST;
        report.targetUrl = targetUrl;
        report.reason = reason;
        report.rightsBasis = rightsBasis;
        report.contactEmail = contactEmail;
        return report;
    }

    /** 대상 지정(권리 침해 주소 해석 또는 관리자 지정, 005 research M3). */
    public void assignTarget(ReportTargetType targetType, Long targetId, User targetUser, Blog targetBlog) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetUser = targetUser;
        this.targetBlog = targetBlog;
    }

    public boolean hasTarget() {
        return targetType != null && targetId != null;
    }

    public boolean isPending() {
        return status == ReportStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public ReportChannel getChannel() {
        return channel;
    }

    public User getReporter() {
        return reporter;
    }

    public ReportTargetType getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public User getTargetUser() {
        return targetUser;
    }

    public Blog getTargetBlog() {
        return targetBlog;
    }

    public ReportReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public String getRightsBasis() {
        return rightsBasis;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public ReportAction getAction() {
        return action;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public User getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }
}
