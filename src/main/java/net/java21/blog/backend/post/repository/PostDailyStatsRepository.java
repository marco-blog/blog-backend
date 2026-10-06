package net.java21.blog.backend.post.repository;

import java.time.Instant;
import java.time.LocalDate;

import net.java21.blog.backend.post.domain.PostDailyStat;
import net.java21.blog.backend.post.domain.PostDailyStatId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 글 일별 신호 쓰기(003 research P4). 엔티티를 읽어 바꾸지 않고 {@code INSERT ... ON DUPLICATE KEY UPDATE} 한 문장으로 올린다
 * (같은 글·같은 날의 동시 요청도 행 하나, 수는 요청 수만큼). H2 MySQL 모드도 같은 문장을 지원한다. 각 메서드는 쿼리 1회.
 */
public interface PostDailyStatsRepository extends JpaRepository<PostDailyStat, PostDailyStatId> {

    /** 그날 조회 1을 더한다(행이 없으면 만든다). */
    @Modifying
    @Query(value = "INSERT INTO post_daily_stats (post_id, stat_date, views, read_completes, created_at, updated_at)"
            + " VALUES (:postId, :date, 1, 0, :now, :now)"
            + " ON DUPLICATE KEY UPDATE views = views + 1, updated_at = :now", nativeQuery = true)
    int upsertView(@Param("postId") Long postId, @Param("date") LocalDate date, @Param("now") Instant now);

    /** 그날 끝까지 읽음 1을 더한다(행이 없으면 만든다). */
    @Modifying
    @Query(value = "INSERT INTO post_daily_stats (post_id, stat_date, views, read_completes, created_at, updated_at)"
            + " VALUES (:postId, :date, 0, 1, :now, :now)"
            + " ON DUPLICATE KEY UPDATE read_completes = read_completes + 1, updated_at = :now", nativeQuery = true)
    int upsertReadComplete(@Param("postId") Long postId, @Param("date") LocalDate date, @Param("now") Instant now);

    /** {@code stat_date < date}인 행을 최대 {@code limit}개 지운다. @return 지운 행 수 */
    @Modifying
    @Query(value = "DELETE FROM post_daily_stats WHERE stat_date < :date LIMIT :limit", nativeQuery = true)
    int deleteOlderThan(@Param("date") LocalDate date, @Param("limit") int limit);
}
