package net.java21.blog.backend.trackback;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 트랙백 설정(005 contracts/api.md "프로퍼티", {@code blog.trackback.*}). 잘못된 값이면 기동하지 않는다. 받기·보내기는 US3가 쓴다.
 *
 * @param receiveLimit        같은 출처 IP의 수신 한도({@code receiveWindow} 안)
 * @param receiveWindow       수신 한도 창
 * @param connectTimeout      보내기 연결 시간 제한
 * @param readTimeout         보내기 응답 시간 제한
 * @param maxTargets          한 번에 보낼 주소 수
 * @param executorThreads     보내기 스레드 수
 * @param executorQueue       보내기 대기열 크기
 * @param recoverPendingAfter 기동 때 이보다 오래된 PENDING을 다시 보낸다
 */
@ConfigurationProperties("blog.trackback")
public record TrackbackProperties(
        @DefaultValue("10") int receiveLimit,
        @DefaultValue("10m") Duration receiveWindow,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("10") int maxTargets,
        @DefaultValue("2") int executorThreads,
        @DefaultValue("100") int executorQueue,
        @DefaultValue("5m") Duration recoverPendingAfter) {

    public TrackbackProperties {
        atLeast("receive-limit", receiveLimit, 1);
        positive("receive-window", receiveWindow);
        positive("connect-timeout", connectTimeout);
        positive("read-timeout", readTimeout);
        atLeast("max-targets", maxTargets, 1);
        atLeast("executor-threads", executorThreads, 1);
        atLeast("executor-queue", executorQueue, 1);
        positive("recover-pending-after", recoverPendingAfter);
    }

    /** 기본값(테스트용). */
    public static TrackbackProperties defaults() {
        return new TrackbackProperties(10, Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(5), 10, 2,
                100, Duration.ofMinutes(5));
    }

    private static void atLeast(String name, int value, int min) {
        if (value < min) {
            throw new IllegalArgumentException("blog.trackback." + name + " must be at least " + min);
        }
    }

    private static void positive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("blog.trackback." + name + " must be positive");
        }
    }
}
