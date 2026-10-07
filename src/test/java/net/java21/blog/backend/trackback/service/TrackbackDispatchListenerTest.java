package net.java21.blog.backend.trackback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.trackback.TrackbackExecutor;
import net.java21.blog.backend.trackback.TrackbackUrls;
import net.java21.blog.backend.trackback.domain.PingErrorCode;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.repository.TrackbackPingLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 커밋 뒤 트랙백 보내기(005 T091, FR-052, research M15). */
@ExtendWith(MockitoExtension.class)
class TrackbackDispatchListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    /** 맡긴 일을 바로 실행하거나(기본) 대기열이 찬 것처럼 거절한다. */
    static final class InlineExecutor extends TrackbackExecutor {
        boolean full;

        InlineExecutor() {
            super(1, 1);
        }

        @Override
        public boolean trySubmit(Runnable task) {
            if (full) {
                return false;
            }
            task.run();
            return true;
        }
    }

    @Mock
    private PostRepository postRepository;
    @Mock
    private TrackbackPingLogRepository pingLogRepository;
    @Mock
    private TrackbackReceiveService receiveService;
    @Mock
    private TrackbackPinger pinger;

    private final InlineExecutor executor = new InlineExecutor();
    private final Map<Long, TrackbackPingLog> logs = new HashMap<>();
    private TrackbackDispatchListener listener;
    private Post post;

    @BeforeEach
    void setUp() {
        listener = new TrackbackDispatchListener(executor, postRepository, pingLogRepository, receiveService, pinger,
                new TrackbackUrls(new SiteProperties("https://blog.java21.net")),
                new TransactionTemplate(mock(PlatformTransactionManager.class)), new MutableClock(NOW));
        Blog blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        blog.changeTitle("마르코 블로그");
        post = TestEntities.post(100L, blog, "보내는 글");
        post.publish("보내는 글", "본문", "<p>본문</p>", "본문", "글 요약", null, PostVisibility.PUBLIC, true, NOW);
        lenient().when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        lenient().when(pingLogRepository.findAllById(any())).thenAnswer(i -> {
            Iterable<Long> ids = i.getArgument(0);
            List<TrackbackPingLog> found = new java.util.ArrayList<>();
            ids.forEach(id -> {
                if (logs.containsKey(id)) {
                    found.add(logs.get(id));
                }
            });
            return found;
        });
        lenient().when(pingLogRepository.findById(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(logs.get((Long) i.getArgument(0))));
    }

    private TrackbackPingLog log(long id, String url) {
        TrackbackPingLog log = TestEntities.with(new TrackbackPingLog(post, url), "id", id);
        logs.put(id, log);
        return log;
    }

    @Test
    void externalTargetsArePingedAndResultsRecordedPerTarget() {
        TrackbackPingLog ok = log(1L, "https://ext.example/tb/1");
        TrackbackPingLog failed = log(2L, "https://ext.example/tb/2");
        when(pinger.ping(eq("https://ext.example/tb/1"), any())).thenReturn(TrackbackPinger.Result.SUCCESS);
        when(pinger.ping(eq("https://ext.example/tb/2"), any()))
                .thenReturn(TrackbackPinger.Result.failure(PingErrorCode.HTTP_ERROR, "HTTP 500"));

        listener.onSendRequested(new TrackbackSendRequested(100L, List.of(1L, 2L)));

        ArgumentCaptor<TrackbackPinger.Payload> payload = ArgumentCaptor.forClass(TrackbackPinger.Payload.class);
        verify(pinger).ping(eq("https://ext.example/tb/1"), payload.capture());
        assertThat(payload.getValue()).isEqualTo(new TrackbackPinger.Payload("https://blog.java21.net/marco/100",
                "보내는 글", "글 요약", "마르코 블로그"));
        assertThat(ok.getStatus()).isEqualTo(PingStatus.SUCCESS);
        assertThat(ok.getAttemptedAt()).isEqualTo(NOW);
        assertThat(failed.getStatus()).isEqualTo(PingStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo(PingErrorCode.HTTP_ERROR);
        assertThat(failed.getErrorMessage()).isEqualTo("HTTP 500");
        assertThat(failed.getAttemptedAt()).isEqualTo(NOW);
    }

    @Test
    void internalTargetsAreReceivedWithoutHttp() {
        TrackbackPingLog accepted = log(1L, "https://blog.java21.net/other/7/trackback");
        TrackbackPingLog refused = log(2L, "https://blog.java21.net/other/8");
        when(receiveService.receiveInternal(post, "other", 7L)).thenReturn(ReceiveOutcome.ACCEPTED);
        when(receiveService.receiveInternal(post, "other", 8L)).thenReturn(ReceiveOutcome.NOT_ALLOWED);

        listener.dispatch(new TrackbackSendRequested(100L, List.of(1L, 2L)));

        verifyNoInteractions(pinger);
        assertThat(accepted.getStatus()).isEqualTo(PingStatus.SUCCESS);
        assertThat(refused.getStatus()).isEqualTo(PingStatus.FAILED);
        assertThat(refused.getErrorCode()).isEqualTo(PingErrorCode.REMOTE_ERROR);
        assertThat(refused.getErrorMessage()).isEqualTo("Trackback is not allowed");
    }

    @Test
    void oneFailingTargetDoesNotAffectOthers() {
        TrackbackPingLog boom = log(1L, "https://ext.example/boom");
        TrackbackPingLog fine = log(2L, "https://ext.example/fine");
        when(pinger.ping(eq("https://ext.example/boom"), any())).thenThrow(new IllegalStateException("boom"));
        when(pinger.ping(eq("https://ext.example/fine"), any())).thenReturn(TrackbackPinger.Result.SUCCESS);

        listener.dispatch(new TrackbackSendRequested(100L, List.of(1L, 2L)));

        assertThat(boom.getStatus()).isEqualTo(PingStatus.FAILED);
        assertThat(boom.getErrorMessage()).isEqualTo("Unexpected error");
        assertThat(fine.getStatus()).isEqualTo(PingStatus.SUCCESS);
    }

    @Test
    void postThatIsNoLongerBodyVisibleDropsItsPendingLogsWithoutSending() {
        log(1L, "https://ext.example/tb");
        TestEntities.with(post, "visibility", PostVisibility.PRIVATE);

        listener.dispatch(new TrackbackSendRequested(100L, List.of(1L)));

        verify(pingLogRepository).deleteByIdsAndStatus(List.of(1L), PingStatus.PENDING);
        verifyNoInteractions(pinger, receiveService);
    }

    @Test
    void missingPostAlsoDropsTheLogs() {
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.empty());

        listener.dispatch(new TrackbackSendRequested(100L, List.of(1L)));

        verify(pingLogRepository).deleteByIdsAndStatus(List.of(1L), PingStatus.PENDING);
    }

    @Test
    void alreadyFinishedOrDeletedLogsAreSkipped() {
        TrackbackPingLog done = log(1L, "https://ext.example/done");
        done.succeed(NOW.minusSeconds(60));

        listener.dispatch(new TrackbackSendRequested(100L, List.of(1L, 99L)));

        verify(pinger, never()).ping(anyString(), any());
        assertThat(done.getAttemptedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void fullQueueMarksTheLogsFailed() {
        TrackbackPingLog a = log(1L, "https://ext.example/a");
        executor.full = true;

        assertThat(listener.submit(new TrackbackSendRequested(100L, List.of(1L)))).isFalse();

        assertThat(a.getStatus()).isEqualTo(PingStatus.FAILED);
        assertThat(a.getErrorCode()).isEqualTo(PingErrorCode.REMOTE_ERROR);
        assertThat(a.getErrorMessage()).isEqualTo("Queue full");
        verifyNoInteractions(pinger);
    }

    @Test
    void realExecutorRunsTasksAndRefusesWhenFull() throws Exception {
        TrackbackExecutor real = new TrackbackExecutor(1, 1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(2);
        Runnable blocking = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.countDown();
        };
        assertThat(real.trySubmit(blocking)).isTrue();
        assertThat(real.trySubmit(ran::countDown)).isTrue();
        assertThat(real.trySubmit(() -> { })).as("스레드 1 + 대기열 1이 찼다").isFalse();
        release.countDown();
        assertThat(ran.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        real.destroy();
        assertThat(real.trySubmit(() -> { })).isFalse();
    }
}
