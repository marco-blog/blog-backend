package net.java21.blog.backend.trackback.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.trackback.TrackbackExecutor;
import net.java21.blog.backend.trackback.TrackbackExecutorConfig;
import net.java21.blog.backend.trackback.TrackbackUrls;
import net.java21.blog.backend.trackback.domain.PingErrorCode;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.repository.TrackbackPingLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커밋 뒤 트랙백 보내기(005 FR-052, research M15). 요청 트랜잭션이 커밋되면 {@code trackbackExecutor}에 맡기고(대기열이 차면 그 기록을
 * FAILED {@code REMOTE_ERROR} "Queue full"로), 실행 스레드에서 {@link #dispatch}한다.
 * <ul>
 *   <li>보내기 직전 글이 더는 본문 노출 가능이 아니면(비공개로 바꿈·휴지통·숨김·정지) 그 PENDING 기록을 지우고 보내지 않는다.</li>
 *   <li>서비스 안 주소는 HTTP 없이 {@link TrackbackReceiveService#receiveInternal}(받지 못하면 {@code REMOTE_ERROR}와 그 메시지),
 *       밖 주소는 {@link TrackbackPinger}.</li>
 *   <li>대상마다 결과를 따로 짧은 트랜잭션으로 남겨 한 대상의 실패가 다른 대상에 영향을 주지 않는다.</li>
 * </ul>
 * {@code @Async} 대신 실행기에 직접 맡기는 것은 대기열이 찼을 때 그 기록을 실패로 남기기 위해서다.
 */
@Component
public class TrackbackDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(TrackbackDispatchListener.class);
    static final String QUEUE_FULL = "Queue full";

    private final TrackbackExecutor executor;
    private final PostRepository postRepository;
    private final TrackbackPingLogRepository pingLogRepository;
    private final TrackbackReceiveService receiveService;
    private final TrackbackPinger pinger;
    private final TrackbackUrls urls;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public TrackbackDispatchListener(@Qualifier(TrackbackExecutorConfig.EXECUTOR) TrackbackExecutor executor,
            PostRepository postRepository, TrackbackPingLogRepository pingLogRepository,
            TrackbackReceiveService receiveService, TrackbackPinger pinger, TrackbackUrls urls,
            TransactionTemplate transactionTemplate, Clock clock) {
        this.executor = executor;
        this.postRepository = postRepository;
        this.pingLogRepository = pingLogRepository;
        this.receiveService = receiveService;
        this.pinger = pinger;
        this.urls = urls;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSendRequested(TrackbackSendRequested event) {
        submit(event);
    }

    /** 실행기에 맡긴다. 대기열이 차면 그 기록을 FAILED로 남기고 false. */
    public boolean submit(TrackbackSendRequested event) {
        if (executor.trySubmit(() -> dispatch(event))) {
            return true;
        }
        log.warn("Trackback queue full: post={}, logs={}", event.postId(), event.logIds().size());
        for (Long id : event.logIds()) {
            record(id, PingErrorCode.REMOTE_ERROR, QUEUE_FULL);
        }
        return false;
    }

    /** 한 글의 PENDING 기록을 보낸다(실행 스레드). */
    public void dispatch(TrackbackSendRequested event) {
        Optional<Post> source = transactionTemplate.execute(status ->
                postRepository.findWithBlogAndOwner(event.postId()));
        if (source == null || source.isEmpty() || !PostExposure.isBodyVisible(source.get())) {
            transactionTemplate.executeWithoutResult(status ->
                    pingLogRepository.deleteByIdsAndStatus(event.logIds(), PingStatus.PENDING));
            return;
        }
        Post post = source.get();
        List<TrackbackPingLog> pending = pingLogRepository.findAllById(event.logIds()).stream()
                .filter(l -> l.getStatus() == PingStatus.PENDING)
                .toList();
        for (TrackbackPingLog target : pending) {
            TrackbackPinger.Result result;
            try {
                result = send(post, target.getTargetUrl());
            } catch (RuntimeException e) {
                log.warn("Trackback send failed unexpectedly: log={}", target.getId(), e);
                result = TrackbackPinger.Result.failure(PingErrorCode.REMOTE_ERROR, "Unexpected error");
            }
            record(target.getId(), result.code(), result.message());
        }
    }

    private TrackbackPinger.Result send(Post post, String targetUrl) {
        Optional<TrackbackUrls.InternalTarget> internal = urls.internalTarget(targetUrl);
        if (internal.isPresent()) {
            ReceiveOutcome outcome = receiveService.receiveInternal(post, internal.get().handle(),
                    internal.get().postId());
            return outcome.accepted() ? TrackbackPinger.Result.SUCCESS
                    : TrackbackPinger.Result.failure(PingErrorCode.REMOTE_ERROR, outcome.message());
        }
        String handle = post.getBlog().getHandle();
        return pinger.ping(targetUrl, new TrackbackPinger.Payload(urls.postUrl(handle, post.getId()),
                post.getTitle(), post.getSummary(), post.getBlog().getTitle()));
    }

    /** 결과를 남긴다({@code code}가 null이면 성공). 그 사이 지워졌거나 이미 끝난 기록은 그대로 둔다. */
    private void record(Long logId, PingErrorCode code, String message) {
        transactionTemplate.executeWithoutResult(status -> pingLogRepository.findById(logId)
                .filter(l -> l.getStatus() == PingStatus.PENDING)
                .ifPresent(l -> {
                    if (code == null) {
                        l.succeed(clock.instant());
                    } else {
                        l.fail(code, message, clock.instant());
                    }
                }));
    }
}
