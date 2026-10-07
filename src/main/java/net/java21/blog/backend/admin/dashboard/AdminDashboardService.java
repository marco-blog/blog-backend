package net.java21.blog.backend.admin.dashboard;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.admin.AdminProperties;
import net.java21.blog.backend.admin.dashboard.dto.AdminDashboardResponse;
import net.java21.blog.backend.admin.user.AdminUserRepository;
import net.java21.blog.backend.user.domain.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 콘솔 대시보드(006 FR-103, research A3). 날짜 경계는 요청한 관리자의 {@code users.time_zone}이고, 계산 결과는 시간대별로
 * {@code blog.admin.dashboard-cache-ttl}(기본 5분, 0이면 캐시 끔) 동안 Caffeine 캐시에 둔다(backend 1대 전제, 001 R26).
 * 처리 대기 신고 수만은 캐시하지 않고 매번 {@link PendingReportCounter}에 묻는다.
 */
@Service
public class AdminDashboardService {

    static final int CACHE_MAX_SIZE = 50;

    private final AdminDashboardQueryRepository repository;
    private final AdminUserRepository adminUserRepository;
    private final PendingReportCounter pendingReportCounter;
    private final Clock clock;
    private final Cache<ZoneId, Snapshot> cache;

    /** 계산한 수치와 계산 시각. */
    record Snapshot(DashboardCounts counts, Instant generatedAt) {
    }

    @Autowired
    public AdminDashboardService(AdminDashboardQueryRepository repository, AdminUserRepository adminUserRepository,
            PendingReportCounter pendingReportCounter, AdminProperties properties, Clock clock) {
        this(repository, adminUserRepository, pendingReportCounter, properties, clock, Ticker.systemTicker());
    }

    /** 테스트에서 캐시 시각을 고정할 때. */
    AdminDashboardService(AdminDashboardQueryRepository repository, AdminUserRepository adminUserRepository,
            PendingReportCounter pendingReportCounter, AdminProperties properties, Clock clock, Ticker ticker) {
        this.repository = repository;
        this.adminUserRepository = adminUserRepository;
        this.pendingReportCounter = pendingReportCounter;
        this.clock = clock;
        Duration ttl = properties.dashboardCacheTtl();
        this.cache = ttl.isZero() ? null : Caffeine.newBuilder()
                .expireAfterWrite(ttl.toNanos(), TimeUnit.NANOSECONDS)
                .maximumSize(CACHE_MAX_SIZE)
                .ticker(ticker)
                .build();
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard(long adminId) {
        ZoneId zone = zoneOf(adminId);
        Snapshot snapshot = cache == null ? compute(zone) : cache.get(zone, this::compute);
        return AdminDashboardResponse.of(snapshot.counts(), pendingReportCounter.countPending(), zone.getId(),
                snapshot.generatedAt());
    }

    private Snapshot compute(ZoneId zone) {
        Instant now = clock.instant();
        return new Snapshot(repository.compute(zone, now), now);
    }

    /** 관리자의 시간대. 읽을 수 없는 값이면 서비스 기본 시간대. */
    private ZoneId zoneOf(long adminId) {
        String id = adminUserRepository.findTimeZoneById(adminId).orElse(User.DEFAULT_TIME_ZONE);
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return ZoneId.of(User.DEFAULT_TIME_ZONE);
        }
    }
}
