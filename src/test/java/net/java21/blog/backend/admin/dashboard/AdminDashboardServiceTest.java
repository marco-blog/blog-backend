package net.java21.blog.backend.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import net.java21.blog.backend.admin.AdminProperties;
import net.java21.blog.backend.admin.dashboard.dto.AdminDashboardResponse;
import net.java21.blog.backend.admin.report.ReportPendingCounter;
import net.java21.blog.backend.admin.report.ReportQueryRepository;
import net.java21.blog.backend.admin.user.AdminUserRepository;
import net.java21.blog.backend.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * 006 T021(FR-103, research A3): 시간대별 캐시, TTL 만료, TTL 0이면 캐시 끔, 처리 대기 신고 수는 캐시하지 않음.
 * T039: 대기 신고 수 빈은 005 {@link ReportPendingCounter}.
 */
class AdminDashboardServiceTest {

    private static final Instant T = Instant.parse("2026-10-07T03:00:00Z");
    private static final DashboardCounts COUNTS = new DashboardCounts(1, 2, 3, 10, 4, 7,
            List.of(new DashboardCounts.Day(LocalDate.of(2026, 10, 7), 1, 2)));

    private final AdminDashboardQueryRepository repository = mock(AdminDashboardQueryRepository.class);
    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final PendingReportCounter pending = mock(PendingReportCounter.class);
    private final MutableClock clock = new MutableClock(T);
    private final AtomicLong nanos = new AtomicLong();

    @BeforeEach
    void setUp() {
        when(repository.compute(any(), any())).thenReturn(COUNTS);
        when(users.findTimeZoneById(1L)).thenReturn(Optional.of("Asia/Seoul"));
        when(users.findTimeZoneById(2L)).thenReturn(Optional.of("UTC"));
        when(users.findTimeZoneById(3L)).thenReturn(Optional.of("Mars/Base"));
        when(users.findTimeZoneById(4L)).thenReturn(Optional.empty());
    }

    private AdminDashboardService service(Duration ttl) {
        return new AdminDashboardService(repository, users, pending, new AdminProperties(null, ttl, null), clock,
                nanos::get);
    }

    @Test
    void cachesPerTimeZoneUntilTtl() {
        AdminDashboardService service = service(Duration.ofMinutes(5));
        when(pending.countPending()).thenReturn(null, 4L, 5L);

        AdminDashboardResponse first = service.dashboard(1L);
        assertThat(first.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(first.generatedAt()).isEqualTo(T);
        assertThat(first.today().signups()).isEqualTo(1);
        assertThat(first.totals().publicPosts()).isEqualTo(7);
        assertThat(first.trend()).singleElement().satisfies(d -> assertThat(d.publishedPosts()).isEqualTo(2));
        assertThat(first.pendingReports()).isNull();

        clock.advance(Duration.ofMinutes(4));
        nanos.addAndGet(Duration.ofMinutes(4).toNanos());
        AdminDashboardResponse cached = service.dashboard(1L);
        assertThat(cached.generatedAt()).as("캐시된 계산 시각").isEqualTo(T);
        assertThat(cached.pendingReports()).as("대기 신고 수는 매번").isEqualTo(4L);
        verify(repository, times(1)).compute(eq(ZoneId.of("Asia/Seoul")), any());

        service.dashboard(2L);
        verify(repository).compute(eq(ZoneId.of("UTC")), any());

        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        AdminDashboardResponse expired = service.dashboard(1L);
        assertThat(expired.generatedAt()).isEqualTo(T.plus(Duration.ofMinutes(4)));
        assertThat(expired.pendingReports()).isEqualTo(5L);
        verify(repository, times(2)).compute(eq(ZoneId.of("Asia/Seoul")), any());
    }

    @Test
    void zeroTtlComputesEveryTime() {
        AdminDashboardService service = service(Duration.ZERO);
        service.dashboard(1L);
        service.dashboard(1L);
        verify(repository, times(2)).compute(eq(ZoneId.of("Asia/Seoul")), any());
    }

    @Test
    void unknownOrMissingTimeZoneFallsBackToServiceDefault() {
        AdminDashboardService service = service(Duration.ZERO);
        assertThat(service.dashboard(3L).timeZone()).isEqualTo("Asia/Seoul");
        assertThat(service.dashboard(4L).timeZone()).isEqualTo("Asia/Seoul");
    }

    /** 006 T039: 관리자 패키지를 훑으면 대기 신고 수 구현은 005 신고 묶음 수를 쓰는 {@link ReportPendingCounter} 하나뿐이다. */
    @Test
    void reportPendingCounterIsTheRegisteredBean() {
        ReportQueryRepository reports = mock(ReportQueryRepository.class);
        when(reports.countPendingGroups()).thenReturn(6L);
        new ApplicationContextRunner()
                .withBean(ReportQueryRepository.class, () -> reports)
                .withUserConfiguration(PendingCounterScan.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PendingReportCounter.class);
                    assertThat(context.getBean(PendingReportCounter.class)).isInstanceOf(ReportPendingCounter.class);
                    assertThat(context.getBean(PendingReportCounter.class).countPending()).isEqualTo(6L);
                });
        verify(reports).countPendingGroups();
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = AdminProperties.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = PendingReportCounter.class))
    static class PendingCounterScan {
    }
}
