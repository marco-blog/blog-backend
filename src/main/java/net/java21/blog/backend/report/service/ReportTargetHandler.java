package net.java21.blog.backend.report.service;

import net.java21.blog.backend.report.domain.ReportTargetType;

/**
 * 신고 대상 종류 하나의 규칙(005 research M4). 1.0은 글·댓글·방명록·트랙백 네 구현이고, 007이 외부 글·외부 블로그 구현을 빈으로
 * 더한다({@link ReportTargetHandlers}가 빈 목록으로 모은다). 관리자 미리보기는 {@code ReportTargetPreviewRepository}가 종류별 IN 쿼리로
 * 만든다.
 */
public interface ReportTargetHandler {

    ReportTargetType type();

    /**
     * 신고자 기준으로 대상을 읽는다. 신고자가 볼 수 없는 대상(남의 비공개 글, 볼 수 없는 비밀 글, 숨김·삭제)은 존재를 드러내지 않는다.
     *
     * @throws net.java21.blog.backend.common.error.BusinessException 404 {@code REPORT_TARGET_NOT_FOUND},
     *                                                                422 {@code CANNOT_REPORT_OWN_CONTENT}
     */
    ReportTarget resolveForReporter(long targetId, long reporterId);

    /**
     * 관리자 기준(대상 지정·주소 해석): 있고 삭제되지 않은 대상이면 숨김 상태여도 돌려준다.
     *
     * @throws net.java21.blog.backend.common.error.BusinessException 404 {@code CONTENT_NOT_FOUND}
     */
    ReportTarget resolveForAdmin(long targetId);

    /**
     * 대상 숨김. 이미 숨김이면 그대로(멱등).
     *
     * @throws net.java21.blog.backend.common.error.BusinessException 404 {@code CONTENT_NOT_FOUND}(없음·삭제)
     */
    HideChange hide(long targetId);

    /** 숨김 해제. 숨김이 아니면 그대로. 없거나 삭제면 404 {@code CONTENT_NOT_FOUND}. */
    HideChange unhide(long targetId);
}
