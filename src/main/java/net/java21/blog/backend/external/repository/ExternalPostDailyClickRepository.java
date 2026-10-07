package net.java21.blog.backend.external.repository;

import java.time.Instant;
import java.time.LocalDate;

import net.java21.blog.backend.external.domain.ExternalPostDailyClick;
import net.java21.blog.backend.external.domain.ExternalPostDailyClickId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 외부 글 일별 클릭(007 research E14). 쓰기는 {@code INSERT ... ON DUPLICATE KEY UPDATE} 한 문장(H2 MySQL 모드도 지원).
 */
public interface ExternalPostDailyClickRepository extends JpaRepository<ExternalPostDailyClick, ExternalPostDailyClickId> {

    @Modifying
    @Query(value = "INSERT INTO external_post_daily_clicks (external_post_id, click_date, clicks, created_at, updated_at)"
            + " VALUES (:postId, :date, 1, :now, :now)"
            + " ON DUPLICATE KEY UPDATE clicks = clicks + 1, updated_at = :now", nativeQuery = true)
    int upsertClick(@Param("postId") Long postId, @Param("date") LocalDate date, @Param("now") Instant now);

    /** {@code click_date < date}인 행을 최대 {@code limit}개 지운다. */
    @Modifying
    @Query(value = "DELETE FROM external_post_daily_clicks WHERE click_date < :date LIMIT :limit", nativeQuery = true)
    int deleteOlderThan(@Param("date") LocalDate date, @Param("limit") int limit);
}
