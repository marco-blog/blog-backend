package net.java21.blog.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import net.java21.blog.backend.support.TestSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 실제 스키마(db/schema-mysql.sql, Crowfoot "blog 1.0")에 대해 모든 운영 엔티티 매핑이 {@code ddl-auto=validate}를 통과한다(T030).
 * 컨텍스트가 뜨면 통과한 것이다. 엔티티를 더할 때마다 이 테스트가 확인한다.
 * 테스트 MySQL 스키마({@code BLOG_TEST_DATASOURCE_*})가 없으면 건너뛴다({@code @MySqlRepositoryTest}와 같은 조건).
 */
@EnabledIfEnvironmentVariable(named = "BLOG_TEST_DATASOURCE_URL", matches = ".+",
        disabledReason = "BLOG_TEST_DATASOURCE_URL 미설정: 테스트 MySQL 스키마가 없어 DB 테스트를 건너뜁니다")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${BLOG_TEST_DATASOURCE_URL}",
        "spring.datasource.username=${BLOG_TEST_DATASOURCE_USERNAME}",
        "spring.datasource.password=${BLOG_TEST_DATASOURCE_PASSWORD}"
})
@Import(TestSchemaInitializer.class)
class EntitySchemaValidationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    @Test
    void allEntitiesMatchRealSchema() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .allSatisfy(entity -> assertThat(entity.getJavaType().getPackageName())
                        .startsWith("net.java21.blog.backend"));
    }
}
