package net.java21.blog.backend.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import net.java21.blog.backend.common.time.TimeConfig;
import net.java21.blog.backend.config.JpaAuditingConfig;
import net.java21.blog.backend.crypto.CryptoConfig;
import net.java21.blog.backend.config.QuerydslConfig;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Repository 슬라이스 테스트용 합성 애너테이션(H2, 원칙 v2.5.0 / research R13).
 *
 * <ul>
 *   <li>{@code @DataJpaTest} + H2 메모리 DB(MySQL 모드). 스키마는 엔티티로 만든다({@code ddl-auto=create-drop}).</li>
 *   <li>접속 정보를 {@code @TestPropertySource}로 지정해 {@code BLOG_TEST_DATASOURCE_*}·{@code SPRING_DATASOURCE_*}
 *       환경 변수가 있어도 MySQL에 붙지 않는다(환경 변수보다 우선순위가 높다). {@link TestSchemaInitializer}도 쓰지 않는다.</li>
 *   <li>Hibernate 통계를 켜서 {@link QueryCounter}로 실행된 쿼리 수(N+1 없음)를 확인한다.</li>
 *   <li>QueryDSL({@link QuerydslConfig}), JPA Auditing({@link JpaAuditingConfig})과 UTC Clock({@link TimeConfig})을 함께 올린다.
 *       시각을 고정하려면 테스트에서 {@code @Primary} {@link MutableClock} 빈을 더한다.</li>
 *   <li>개인정보 암호화({@link CryptoConfig}): 회원 엔티티의 {@code EncryptedStringConverter}가 쓴다(test 프로필의 고정 키).</li>
 * </ul>
 * MySQL 전용 쿼리(FULLTEXT ngram 등)는 {@link MySqlRepositoryTest}로 확인한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:blogtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import({QuerydslConfig.class, JpaAuditingConfig.class, TimeConfig.class, QueryCounter.class, CryptoConfig.class})
public @interface JpaRepositoryTest {
}
