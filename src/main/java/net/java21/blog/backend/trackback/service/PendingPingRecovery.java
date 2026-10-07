package net.java21.blog.backend.trackback.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.repository.TrackbackPingLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 복구(005 research M15): 기동 직후 1회, {@code blog.trackback.recover-pending-after}(5분)보다 오래된 PENDING 중 글이 발행 상태인
 * 것을 다시 보낸다(재기동으로 잃은 요청). 예약 글의 PENDING은 그 글이 발행될 때 보내므로 그대로 둔다. 실패해도 기동은 계속한다.
 */
@Component
public class PendingPingRecovery {

    private static final Logger log = LoggerFactory.getLogger(PendingPingRecovery.class);

    private final TrackbackPingLogRepository pingLogRepository;
    private final TrackbackDispatchListener dispatcher;
    private final TrackbackProperties properties;
    private final Clock clock;

    public PendingPingRecovery(TrackbackPingLogRepository pingLogRepository, TrackbackDispatchListener dispatcher,
            TrackbackProperties properties, Clock clock) {
        this.pingLogRepository = pingLogRepository;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int posts = recover();
            if (posts > 0) {
                log.info("Recovered pending trackbacks: posts={}", posts);
            }
        } catch (RuntimeException e) {
            log.warn("Pending trackback recovery failed", e);
        }
    }

    /** 다시 보낸 글 수. */
    public int recover() {
        List<Object[]> rows = pingLogRepository.findStalePending(PingStatus.PENDING,
                clock.instant().minus(properties.recoverPendingAfter()), PostStatus.PUBLISHED);
        Map<Long, List<Long>> byPost = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byPost.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((Long) row[1]);
        }
        byPost.forEach((postId, ids) -> dispatcher.submit(new TrackbackSendRequested(postId, ids)));
        return byPost.size();
    }
}
