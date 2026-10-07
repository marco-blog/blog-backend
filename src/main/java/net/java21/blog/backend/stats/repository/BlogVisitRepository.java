package net.java21.blog.backend.stats.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import net.java21.blog.backend.stats.domain.BlogDailyVisit;
import net.java21.blog.backend.stats.domain.BlogDailyVisitId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 블로그 방문 기록·조회(004 research B9). 쓰기는 엔티티를 읽어 바꾸지 않고 {@code INSERT ... ON DUPLICATE KEY UPDATE} 한 문장과
 * 블로그 전체 수 원자적 UPDATE 한 문장으로 한다(003 {@code post_daily_stats}와 같은 방식, H2 MySQL 모드 지원). 각 메서드는 쿼리 1회.
 */
public interface BlogVisitRepository extends JpaRepository<BlogDailyVisit, BlogDailyVisitId> {

    /** 그날 방문자 1을 더한다(행이 없으면 만든다). */
    @Modifying
    @Query(value = "INSERT INTO blog_daily_visits (blog_id, visit_date, visitors, created_at, updated_at)"
            + " VALUES (:blogId, :date, 1, :now, :now)"
            + " ON DUPLICATE KEY UPDATE visitors = visitors + 1, updated_at = :now", nativeQuery = true)
    int upsertVisit(@Param("blogId") Long blogId, @Param("date") LocalDate date, @Param("now") Instant now);

    /**
     * 블로그 전체 방문자 수를 1 올린다. {@code updated_at = updated_at}: MySQL의 {@code ON UPDATE CURRENT_TIMESTAMP}가 방문만으로
     * 블로그의 수정 시각(피드 ETag)을 바꾸지 않게 한다.
     */
    @Modifying
    @Query(value = "UPDATE blogs SET total_visitors = total_visitors + 1, updated_at = updated_at WHERE id = :blogId",
            nativeQuery = true)
    int incrementTotal(@Param("blogId") Long blogId);

    /** {@code from} ~ {@code to}(둘 다 포함) 날짜의 방문 행(날짜 오름차순). 방문이 없던 날은 행이 없다. */
    @Query("select v from BlogDailyVisit v where v.id.blogId = :blogId and v.id.visitDate between :from and :to"
            + " order by v.id.visitDate asc")
    List<BlogDailyVisit> findRange(@Param("blogId") Long blogId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
