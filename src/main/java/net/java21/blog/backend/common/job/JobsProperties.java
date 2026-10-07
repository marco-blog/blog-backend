package net.java21.blog.backend.common.job;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 정기 작업 설정(contracts/api.md "프로퍼티", research R26).
 *
 * @param trashPurgeCron 휴지통 비우기 주기(FR-084, FR-159). 기본 매일 03:30
 * @param trashRetention 휴지통 보관 기간. 지나면 영구 삭제한다(FR-084)
 * @param purgeBatchSize 한 번(한 트랜잭션)에 처리하는 건수
 * @param notificationPurgeCron 오래된 알림 정리 주기(002 research D3). 기본 매일 04:15
 * @param postStatsPurgeCron 오래된 글 일별 통계 정리 주기(003 research P4). 기본 매일 04:45
 * @param exportCleanupCron 만료된 블로그 백업 정리 주기(004 research B14). 기본 매시 10분
 */
@ConfigurationProperties("blog.jobs")
public record JobsProperties(
        @DefaultValue("0 30 3 * * *") String trashPurgeCron,
        @DefaultValue("30d") Duration trashRetention,
        @DefaultValue("500") int purgeBatchSize,
        @DefaultValue(JobsProperties.DEFAULT_NOTIFICATION_PURGE_CRON) String notificationPurgeCron,
        @DefaultValue(JobsProperties.DEFAULT_POST_STATS_PURGE_CRON) String postStatsPurgeCron,
        @DefaultValue(JobsProperties.DEFAULT_EXPORT_CLEANUP_CRON) String exportCleanupCron) {

    public static final String DEFAULT_NOTIFICATION_PURGE_CRON = "0 15 4 * * *";
    public static final String DEFAULT_POST_STATS_PURGE_CRON = "0 45 4 * * *";
    public static final String DEFAULT_EXPORT_CLEANUP_CRON = "0 10 * * * *";

    /** 알림·통계 정리 주기는 기본값으로 둔다(정기 작업 주기를 다루지 않는 테스트용). */
    public JobsProperties(String trashPurgeCron, Duration trashRetention, int purgeBatchSize) {
        this(trashPurgeCron, trashRetention, purgeBatchSize, DEFAULT_NOTIFICATION_PURGE_CRON);
    }

    public JobsProperties(String trashPurgeCron, Duration trashRetention, int purgeBatchSize,
            String notificationPurgeCron) {
        this(trashPurgeCron, trashRetention, purgeBatchSize, notificationPurgeCron, DEFAULT_POST_STATS_PURGE_CRON);
    }

    public JobsProperties(String trashPurgeCron, Duration trashRetention, int purgeBatchSize,
            String notificationPurgeCron, String postStatsPurgeCron) {
        this(trashPurgeCron, trashRetention, purgeBatchSize, notificationPurgeCron, postStatsPurgeCron,
                DEFAULT_EXPORT_CLEANUP_CRON);
    }

    @ConstructorBinding
    public JobsProperties {
        if (trashRetention.isNegative() || trashRetention.isZero()) {
            throw new IllegalArgumentException("blog.jobs.trash-retention must be positive");
        }
        if (purgeBatchSize < 1) {
            throw new IllegalArgumentException("blog.jobs.purge-batch-size must be at least 1");
        }
    }
}
