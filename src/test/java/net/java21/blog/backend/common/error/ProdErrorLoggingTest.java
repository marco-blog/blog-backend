package net.java21.blog.backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.web.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

/**
 * 로그 최소 범위(T242, {@code logback-spring.xml}). prod 프로필로 로깅을 초기화해
 * <ul>
 *   <li>파일 appender가 일별 롤링·30일 보관이고,</li>
 *   <li>예상하지 못한 오류의 스택과 내부 정보(SQL 등)는 {@code traceId}와 함께 로그 파일에만 남으며</li>
 *   <li>응답 {@code resultMessage}에는 고정 문구만 나가는지(같은 {@code traceId}로 로그와 맞춰 볼 수 있게)</li>
 * </ul>
 * 확인한다. 끝나면 로깅을 기본(test) 구성으로 되돌린다.
 */
class ProdErrorLoggingTest {

    private static final List<String> LOGGING_SYSTEM_PROPERTIES = List.of("LOG_PATH", "LOG_FILE");

    @TempDir
    Path logDir;

    private final LogbackLoggingSystem loggingSystem = new LogbackLoggingSystem(getClass().getClassLoader());
    private final Map<String, String> savedProperties = new HashMap<>();

    @AfterEach
    void restoreDefaultLogging() {
        MDC.remove(RequestIdFilter.MDC_KEY);
        loggingSystem.cleanUp();
        Properties system = System.getProperties();
        for (String name : LOGGING_SYSTEM_PROPERTIES) {
            if (savedProperties.containsKey(name)) {
                system.setProperty(name, savedProperties.get(name));
            } else {
                system.remove(name);
            }
        }
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(new MockEnvironment()), null, null);
    }

    @Test
    void prodWritesDailyRollingFileWithTraceIdAndStackWhileResponseHidesDetails() throws Exception {
        initializeProdLogging();

        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<?> appender = root.getAppender("FILE");
        assertThat(appender).isInstanceOf(RollingFileAppender.class);
        RollingFileAppender<?> file = (RollingFileAppender<?>) appender;
        assertThat(file.getRollingPolicy()).isInstanceOf(TimeBasedRollingPolicy.class);
        TimeBasedRollingPolicy<?> policy = (TimeBasedRollingPolicy<?>) file.getRollingPolicy();
        assertThat(policy.getMaxHistory()).isEqualTo(30);
        assertThat(policy.getFileNamePattern()).endsWith("blog-backend.%d{yyyy-MM-dd,UTC}.log.gz");
        assertThat(Path.of(file.getFile())).isEqualTo(logDir.resolve("blog-backend.log"));

        // 예상하지 못한 오류: 응답은 고정 문구, 로그에는 traceId·원인·스택
        MDC.put(RequestIdFilter.MDC_KEY, "4bf92f3577b34da6");
        ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler().handleUnexpected(
                new IllegalStateException("could not execute statement [select * from users where email_hash=?]"));
        ApiResponse.Header header = response.getBody().header();
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(header.resultCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(header.resultMessage()).isEqualTo("Internal error");
        assertThat(header.traceId()).isEqualTo("4bf92f3577b34da6");

        String log = Files.readString(logDir.resolve("blog-backend.log"), StandardCharsets.UTF_8);
        assertThat(log)
                .contains("[4bf92f3577b34da6]")
                .contains("ERROR")
                .contains("Unhandled exception")
                .contains("java.lang.IllegalStateException: could not execute statement")
                .contains("\tat net.java21.blog.backend.common.error.ProdErrorLoggingTest");
    }

    private void initializeProdLogging() {
        for (String name : LOGGING_SYSTEM_PROPERTIES) {
            String value = System.getProperty(name);
            if (value != null) {
                savedProperties.put(name, value);
            }
        }
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.file.path", logDir.toString());
        environment.setActiveProfiles("prod");
        loggingSystem.cleanUp();
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml", null);
    }
}
