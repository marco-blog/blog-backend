package net.java21.blog.backend.trackback.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 보낸 트랙백 기록 저장소(005 T019, research M15). */
public interface TrackbackPingLogRepository extends JpaRepository<TrackbackPingLog, Long> {

    List<TrackbackPingLog> findByPostIdAndStatus(Long postId, PingStatus status);

    /** 이 글들의 PENDING 기록을 한 번에 지운다(예약 취소·휴지통 이동, 결정 표 25번). 지운 행 수. */
    @Modifying(flushAutomatically = true)
    @Query("delete from TrackbackPingLog l where l.post.id in :postIds and l.status = :status")
    int deleteByPostIdsAndStatus(@Param("postIds") Collection<Long> postIds, @Param("status") PingStatus status);

    /** 이 기록 중 아직 PENDING인 것을 지운다(보내기 직전 글을 더는 볼 수 없을 때). 지운 행 수. */
    @Modifying(flushAutomatically = true)
    @Query("delete from TrackbackPingLog l where l.id in :ids and l.status = :status")
    int deleteByIdsAndStatus(@Param("ids") Collection<Long> ids, @Param("status") PingStatus status);

    /**
     * 기동 복구 대상(005 research M15): {@code before}보다 먼저 만든 PENDING 중 글이 {@code postStatus}(발행)인 것. [글 ID, 기록 ID].
     * 상태 인덱스가 없어 전체를 훑는다(plan.md 선택 제안 2).
     */
    @Query("select l.post.id, l.id from TrackbackPingLog l where l.status = :status and l.createdAt < :before "
            + "and l.post.status = :postStatus order by l.post.id, l.id")
    List<Object[]> findStalePending(@Param("status") PingStatus status, @Param("before") Instant before,
            @Param("postStatus") PostStatus postStatus);
}
