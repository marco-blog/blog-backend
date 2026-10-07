package net.java21.blog.backend.trackback.repository;

import java.util.List;

import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import org.springframework.data.jpa.repository.JpaRepository;

/** 보낸 트랙백 기록 저장소(005 T019). */
public interface TrackbackPingLogRepository extends JpaRepository<TrackbackPingLog, Long> {

    List<TrackbackPingLog> findByPostIdAndStatus(Long postId, PingStatus status);
}
