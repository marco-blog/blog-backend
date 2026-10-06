package net.java21.blog.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트에서 시각을 고정하고 원하는 만큼 앞으로 돌리는 UTC {@link Clock}. 운영 코드는 {@code TimeConfig}의 Clock을 쓴다. */
public final class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    public void set(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("MutableClock은 UTC만 쓴다");
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
