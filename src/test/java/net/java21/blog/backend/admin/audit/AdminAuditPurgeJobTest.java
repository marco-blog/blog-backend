package net.java21.blog.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.admin.AdminProperties;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 006 T042(FR-106 "1년간 보관", research A7): 보관 기간 전 기록만, 묶음 크기씩 트랜잭션을 나눠 지우고 지운 수를 돌려준다. */
class AdminAuditPurgeJobTest {

    private static final Instant NOW = Instant.parse("2027-10-07T00:00:00Z");

    private final AdminAuditPurgeRepository repository = mock(AdminAuditPurgeRepository.class);
    private final TransactionTemplate tx = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };

    private AdminAuditPurgeJob job(int batch) {
        JobsProperties jobs = new JobsProperties("0 0 4 * * *", Duration.ofDays(30), batch);
        return new AdminAuditPurgeJob(repository, tx,
                new AdminProperties(null, null, Duration.ofDays(365)), jobs, new MutableClock(NOW));
    }

    @Test
    void deletesInBatchesUntilShortBatch() {
        Instant cutoff = NOW.minus(Duration.ofDays(365));
        when(repository.findIdsCreatedBefore(cutoff, 2)).thenReturn(List.of(1L, 2L), List.of(3L));
        when(repository.deleteByIds(List.of(1L, 2L))).thenReturn(2L);
        when(repository.deleteByIds(List.of(3L))).thenReturn(1L);

        assertThat(job(2).purge()).isEqualTo(3);
        verify(repository, times(2)).findIdsCreatedBefore(cutoff, 2);
    }

    @Test
    void nothingToDelete() {
        when(repository.findIdsCreatedBefore(NOW.minus(Duration.ofDays(365)), 500)).thenReturn(List.of());
        AdminAuditPurgeJob job = job(500);
        job.run();
        verify(repository).deleteByIds(List.of());
        verify(repository, never()).deleteByIds(List.of(1L));
    }

    @Test
    void stopsWhenAFullBatchDeletesNothing() {
        when(repository.findIdsCreatedBefore(NOW.minus(Duration.ofDays(365)), 1)).thenReturn(List.of(9L));
        when(repository.deleteByIds(anyList())).thenReturn(0L);
        assertThat(job(1).purge()).isZero();
    }

    /** 기록 삭제는 정리 작업만 한다: 기록 저장소에는 여전히 삭제 메서드가 없다(FR-106, 수정·삭제 API 없음). */
    @Test
    void auditLogRepositoryStillHasNoDeleteMethod() {
        assertThat(java.util.Arrays.stream(AdminAuditLogRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
    }
}
