package net.java21.blog.backend.common.job;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 정기 작업 설정(contracts/api.md "프로퍼티", research R26).
 *
 * @param trashPurgeCron 휴지통 비우기 주기(FR-084, FR-159). 기본 매일 03:30
 * @param trashRetention 휴지통 보관 기간. 지나면 영구 삭제한다(FR-084)
 * @param purgeBatchSize 한 번(한 트랜잭션)에 처리하는 건수
 */
@ConfigurationProperties("blog.jobs")
public record JobsProperties(
        @DefaultValue("0 30 3 * * *") String trashPurgeCron,
        @DefaultValue("30d") Duration trashRetention,
        @DefaultValue("500") int purgeBatchSize) {

    public JobsProperties {
        if (trashRetention.isNegative() || trashRetention.isZero()) {
            throw new IllegalArgumentException("blog.jobs.trash-retention must be positive");
        }
        if (purgeBatchSize < 1) {
            throw new IllegalArgumentException("blog.jobs.purge-batch-size must be at least 1");
        }
    }
}
