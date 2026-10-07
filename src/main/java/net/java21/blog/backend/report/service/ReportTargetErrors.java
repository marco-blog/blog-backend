package net.java21.blog.backend.report.service;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.report.domain.ReportTargetType;

/** 처리기 공통 예외. */
final class ReportTargetErrors {

    private ReportTargetErrors() {
    }

    static BusinessException notFound(ReportTargetType type, long id) {
        return new BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND, "Report target not found: " + type + " " + id);
    }

    static BusinessException own(ReportTargetType type, long id) {
        return new BusinessException(ErrorCode.CANNOT_REPORT_OWN_CONTENT, "Cannot report own content: " + type + " "
                + id);
    }

    static BusinessException contentNotFound(ReportTargetType type, long id) {
        return new BusinessException(ErrorCode.CONTENT_NOT_FOUND, "Content not found: " + type + " " + id);
    }
}
