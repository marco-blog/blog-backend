package net.java21.blog.backend.report.repository;

import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.dto.TargetKey;

/** 005 밖 대상 종류(007 {@code EXTERNAL_*})의 관리자 미리보기. 종류마다 IN 쿼리 1회로 {@code out}에 채운다(없는 대상은 비워 둔다). */
public interface TargetPreviewProvider {

    Set<ReportTargetType> types();

    void previews(ReportTargetType type, Set<Long> ids, Map<TargetKey, ReportTargetPreview> out);
}
