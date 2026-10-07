package net.java21.blog.backend.external.fetch;

import java.time.Duration;

/**
 * 실패 뒤 다음 수집까지의 지연(007 research E5): {@code 기준 × 2^(n-1)}, 최대 {@code max-backoff}(12시간). 기준은 수집 주기(기본 30분)라
 * 기본값이면 30분 → 1시간 → 2시간 … 이다.
 */
public final class FetchBackoff {

    private FetchBackoff() {
    }

    public static Duration delay(Duration base, int failures, Duration max) {
        int n = Math.max(1, failures);
        if (n > 30) {
            return max;
        }
        Duration d = base.multipliedBy(1L << (n - 1));
        return d.compareTo(max) > 0 ? max : d;
    }
}
