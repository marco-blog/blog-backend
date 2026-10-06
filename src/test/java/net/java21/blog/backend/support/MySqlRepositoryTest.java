package net.java21.blog.backend.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Repository 슬라이스 테스트용 합성 애너테이션.
 *
 * <ul>
 *   <li>{@code @DataJpaTest} + {@code @AutoConfigureTestDatabase(replace = NONE)} + {@code @ActiveProfiles("test")}</li>
 *   <li>전용 테스트 MySQL 스키마(환경 변수 {@code BLOG_TEST_DATASOURCE_*})에 연결한다. Testcontainers는 쓰지 않는다.</li>
 *   <li>{@code @TestPropertySource}로 접속 정보를 다시 지정해, 개발용 {@code SPRING_DATASOURCE_*} 환경 변수가
 *       있어도 테스트가 개발 DB에 붙지 않게 한다(환경 변수보다 우선순위가 높다).</li>
 *   <li>{@link TestSchemaInitializer}: JVM당 한 번 스냅숏으로 스키마를 다시 만든다(안전장치 포함).</li>
 *   <li>{@code BLOG_TEST_DATASOURCE_URL}이 없으면 테스트를 건너뛴다.</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EnabledIfEnvironmentVariable(named = "BLOG_TEST_DATASOURCE_URL", matches = ".+",
        disabledReason = "BLOG_TEST_DATASOURCE_URL 미설정: 테스트 MySQL 스키마가 없어 DB 테스트를 건너뜁니다 (README의 '테스트 DB' 참고)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${BLOG_TEST_DATASOURCE_URL}",
        "spring.datasource.username=${BLOG_TEST_DATASOURCE_USERNAME}",
        "spring.datasource.password=${BLOG_TEST_DATASOURCE_PASSWORD}"
})
@Import(TestSchemaInitializer.class)
public @interface MySqlRepositoryTest {
}
