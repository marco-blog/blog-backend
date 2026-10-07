package net.java21.blog.backend.report.event;

import java.util.List;

import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;

/**
 * 신고 처리 완료(005 FR-041, research M3·M10). 커밋 뒤 회원 신고자에게 {@code REPORT_RESOLVED} 알림을, 권리 침해 신고자에게 결과 메일을
 * 보낸다. 대상의 제목·내용은 싣지 않는다.
 *
 * @param targetType 대상 종류(대상 미정 권리 침해면 null)
 * @param decision   {@code ACTIONED} 또는 {@code DISMISSED}
 * @param members    회원 신고자(신고 id, 회원 id)
 * @param rights     권리 침해 신고자(신고 id, 연락 이메일, 신고한 주소)
 */
public record ReportResolvedEvent(ReportTargetType targetType, ReportStatus decision, List<MemberRecipient> members,
        List<RightsRecipient> rights) {

    public record MemberRecipient(long reportId, long reporterId) {
    }

    /** 이메일은 메일 발송에만 쓰고 로그에 남기지 않는다. */
    public record RightsRecipient(long reportId, String contactEmail, String targetUrl) {

        @Override
        public String toString() {
            return "RightsRecipient[reportId=" + reportId + "]";
        }
    }
}
