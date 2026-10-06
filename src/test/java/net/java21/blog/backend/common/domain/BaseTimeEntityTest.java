package net.java21.blog.backend.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.EntityManager;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.fixture.audit.FixtureTag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;

/** JPA Auditing이 created_at·updated_at을 채우는지 H2에서 확인한다. */
@JpaRepositoryTest
class BaseTimeEntityTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = FixtureTag.class)
    static class FixtureEntities {
    }

    @Autowired
    private EntityManager em;

    @Test
    void fillsCreatedAtAndUpdatedAtOnPersist() {
        Instant before = Instant.now();
        FixtureTag tag = new FixtureTag("java");

        em.persist(tag);
        em.flush();

        assertThat(tag.getCreatedAt()).isNotNull().isBetween(before, Instant.now());
        assertThat(tag.getUpdatedAt()).isNotNull().isCloseTo(tag.getCreatedAt(), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void updatesOnlyUpdatedAtOnChange() throws InterruptedException {
        FixtureTag tag = new FixtureTag("spring");
        em.persist(tag);
        em.flush();
        Instant createdAt = tag.getCreatedAt();
        Instant firstUpdatedAt = tag.getUpdatedAt();

        Thread.sleep(5);
        tag.rename("spring-boot");
        em.flush();
        em.clear();

        FixtureTag reloaded = em.find(FixtureTag.class, tag.getId());
        assertThat(reloaded.getName()).isEqualTo("spring-boot");
        assertThat(reloaded.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getUpdatedAt()).isAfter(firstUpdatedAt);
    }
}
