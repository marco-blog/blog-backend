package net.java21.blog.backend.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.EntityManager;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.fixture.audit.FixtureTag;
import net.java21.blog.backend.BlogBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** JPA Auditing이 created_at·updated_at을 {@code Clock} 값으로 채우는지 H2에서 확인한다(T025). */
@JpaRepositoryTest
class BaseTimeEntityTest {

    private static final Instant T0 = Instant.parse("2026-10-06T04:24:19.123456Z");

    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {FixtureTag.class, BlogBackendApplication.class})
    static class FixtureEntities {

        @Bean
        @Primary
        MutableClock fixedClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(T0);
    }

    @Test
    void fillsCreatedAtAndUpdatedAtFromClockOnPersist() {
        FixtureTag tag = new FixtureTag("java");

        em.persist(tag);
        em.flush();

        assertThat(tag.getCreatedAt()).isEqualTo(T0);
        assertThat(tag.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void updatesOnlyUpdatedAtOnChange() {
        FixtureTag tag = new FixtureTag("spring");
        em.persist(tag);
        em.flush();

        clock.advance(Duration.ofMinutes(5));
        tag.rename("spring-boot");
        em.flush();
        em.clear();

        FixtureTag reloaded = em.find(FixtureTag.class, tag.getId());
        assertThat(reloaded.getName()).isEqualTo("spring-boot");
        assertThat(reloaded.getCreatedAt()).isEqualTo(T0);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    }
}
