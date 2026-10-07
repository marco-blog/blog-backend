package net.java21.blog.backend.report.domain;

import java.util.EnumSet;
import java.util.Set;

/** 신고 사유(reports.reason, 005 data-model). 권리 침해 양식은 {@link #RIGHTS_REQUEST_REASONS}만 받는다. */
public enum ReportReason {
    SPAM,
    ABUSE,
    ADULT,
    ILLEGAL,
    PRIVACY,
    COPYRIGHT,
    DEFAMATION,
    OTHER;

    /** 권리 침해 신고 양식의 사유(005 contracts/api.md). */
    public static final Set<ReportReason> RIGHTS_REQUEST_REASONS = EnumSet.of(COPYRIGHT, PRIVACY, DEFAMATION, OTHER);
}
