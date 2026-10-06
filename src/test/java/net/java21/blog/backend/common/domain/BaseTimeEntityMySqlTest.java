package net.java21.blog.backend.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.EntityManager;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.fixture.audit.FixtureTag;
import net.java21.blog.backend.BlogBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 스키마({@code tags})에서 {@link BaseTimeEntity}의 매핑을 확인한다.
 * 컨텍스트가 뜨면 {@code ddl-auto=validate}가 Instant ↔ {@code DATETIME(6)} 매핑을 통과한 것이다.
 * 저장된 값이 UTC 그대로인지(서버·JVM 시간대와 무관) 문자열로 읽어 비교한다.
 */
@MySqlRepositoryTest
class BaseTimeEntityMySqlTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {FixtureTag.class, BlogBackendApplication.class})
    static class FixtureEntities {
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void storesAuditTimesAsUtcDatetime6() {
        FixtureTag tag = new FixtureTag("utc-check");
        em.persist(tag);
        em.flush();

        String stored = jdbc.queryForObject(
                "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f') FROM tags WHERE id = ?",
                String.class, tag.getId());
        Instant storedAsUtc = LocalDateTime.parse(stored).toInstant(ZoneOffset.UTC);

        assertThat(storedAsUtc).isCloseTo(tag.getCreatedAt(), within(1, ChronoUnit.MICROS));
        assertThat(storedAsUtc).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }
}
