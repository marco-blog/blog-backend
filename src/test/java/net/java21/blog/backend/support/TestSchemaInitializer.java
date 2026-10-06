package net.java21.blog.backend.support;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.util.StreamUtils;

/**
 * 테스트 DB 스키마를 JVM(테스트 실행)마다 한 번 다시 만든다.
 * <ol>
 *   <li>{@link TestSchemaGuard}로 개발 DB가 아닌지 확인한다.</li>
 *   <li>스냅숏({@code db/schema-mysql.sql}, Crowfoot export_ddl 결과)에 있는 테이블만 지운다.
 *       스냅숏에 없는 테이블은 건드리지 않는다.</li>
 *   <li>스냅숏을 실행한다.</li>
 * </ol>
 * DataSource가 만들어진 직후에 실행되므로 Hibernate의 스키마 확인(ddl-auto=validate)보다 앞선다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSchemaInitializer {

    static final String SNAPSHOT = "db/schema-mysql.sql";

    private static final Pattern CREATE_TABLE = Pattern.compile("(?im)^\\s*CREATE\\s+TABLE\\s+`?(\\w+)`?");
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    @Bean
    static BeanPostProcessor testSchemaInitializerPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && INITIALIZED.compareAndSet(false, true)) {
                    recreate(dataSource);
                }
                return bean;
            }
        };
    }

    static void recreate(DataSource dataSource) {
        Resource snapshot = new ClassPathResource(SNAPSHOT);
        try (Connection connection = dataSource.getConnection()) {
            TestSchemaGuard.check(connection.getCatalog(),
                    System.getenv("BLOG_TEST_ALLOW_CLEAN"),
                    System.getenv("SPRING_DATASOURCE_URL"));
            List<String> tables = tableNames(StreamUtils.copyToString(snapshot.getInputStream(), StandardCharsets.UTF_8));
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : tables) {
                    statement.execute("DROP TABLE IF EXISTS `" + table + "`");
                }
                ScriptUtils.executeSqlScript(connection, snapshot);
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("테스트 DB 스키마를 만들지 못했습니다", e);
        }
    }

    static List<String> tableNames(String ddl) {
        List<String> names = new ArrayList<>();
        Matcher matcher = CREATE_TABLE.matcher(ddl);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
