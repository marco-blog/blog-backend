package net.java21.blog.backend.export;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 블로그 백업 설정(004 contracts/api.md "프로퍼티", research B14). 잘못된 값이면 기동하지 않는다.
 *
 * @param dir           백업 파일 디렉터리(필수. {@code BLOG_DATA_DIR}/exports, local 기본 {@code ./data/exports})
 * @param retention     내려받을 수 있는 기간
 * @param minInterval   블로그당 백업 간격(실패한 백업은 세지 않는다)
 * @param staleRunning  기동 때 이보다 오래된 RUNNING은 FAILED({@code INTERRUPTED})로 바꾼다
 */
@ConfigurationProperties("blog.export")
public record ExportProperties(
        String dir,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("24h") Duration minInterval,
        @DefaultValue("1h") Duration staleRunning) {

    public ExportProperties {
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("blog.export.dir is required");
        }
        requirePositive(retention, "retention");
        requirePositive(minInterval, "min-interval");
        requirePositive(staleRunning, "stale-running");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("blog.export." + name + " must be positive");
        }
    }
}
