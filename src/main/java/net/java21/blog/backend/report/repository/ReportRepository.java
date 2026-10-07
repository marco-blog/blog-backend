package net.java21.blog.backend.report.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 신고 저장·처리(005 T018·T052). 목록·묶음 조회는 {@code ReportQueryRepository}(QueryDSL). */
public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 같은 회원이 같은 대상을 이미 신고했는지(처리 여부와 무관, {@code uk_reports_reporter_target}). */
    boolean existsByReporterIdAndTargetTypeAndTargetId(Long reporterId, ReportTargetType targetType, Long targetId);

    /** 같은 대상의 대기 신고 id(처리 전 닫을 목록). */
    @Query("select r.id from Report r where r.targetType = :type and r.targetId = :targetId and r.status = :status")
    List<Long> findIdsByTargetAndStatus(@Param("type") ReportTargetType type, @Param("targetId") Long targetId,
            @Param("status") ReportStatus status);

    /**
     * 대기 신고를 한 번에 닫는다(조건부 UPDATE 1회, 005 research M3). {@code status = PENDING}인 행만 바꾸므로 이미 처리된 신고와
     * 다른 대상의 신고는 그대로다. 바뀐 행 수.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Report r set r.status = :status, r.action = :action, r.resolutionNote = :note,"
            + " r.handledBy = :admin, r.handledAt = :now, r.updatedAt = :now"
            + " where r.id in :ids and r.status = net.java21.blog.backend.report.domain.ReportStatus.PENDING")
    int resolvePending(@Param("ids") Collection<Long> ids, @Param("status") ReportStatus status,
            @Param("action") ReportAction action, @Param("note") String note, @Param("admin") User admin,
            @Param("now") Instant now);

    /** 닫은 신고 중 회원 신고자 id(중복 없이, 알림용). */
    @Query("select distinct r.reporter.id from Report r where r.id in :ids and r.reporter is not null")
    List<Long> findReporterIds(@Param("ids") Collection<Long> ids);

    /** 닫은 신고 중 연락 이메일이 남은 권리 침해 신고(결과 메일용). */
    @Query("select r from Report r where r.id in :ids"
            + " and r.channel = net.java21.blog.backend.report.domain.ReportChannel.RIGHTS_REQUEST"
            + " and r.contactEmail is not null")
    List<Report> findRightsRequestsWithContact(@Param("ids") Collection<Long> ids);
}
