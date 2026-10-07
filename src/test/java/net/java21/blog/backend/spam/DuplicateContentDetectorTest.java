package net.java21.blog.backend.spam;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 005 T065: 같은 주체·같은 내용은 창 안 3회까지, 4번째 422(FR-144, research M12). */
@ExtendWith(MockitoExtension.class)
class DuplicateContentDetectorTest {

    private static final String SPAM = "Buy cheap pills at example.com now";

    @Mock
    private SystemSettingsService settings;

    private final AtomicLong nanos = new AtomicLong();
    private DuplicateContentDetector detector;

    @BeforeEach
    void setUp() {
        lenient().when(settings.duplicateRule()).thenReturn(new SystemSettingsService.DuplicateRule(10, 3));
        detector = new DuplicateContentDetector(new RateLimiter(nanos::get), settings, SpamProperties.defaults());
    }

    private void passes(String subject, String content) {
        assertThatCode(() -> detector.check(subject, content)).doesNotThrowAnyException();
    }

    @Test
    void fourthSameContentInWindowIsRejected() {
        passes("u:1", SPAM);
        passes("u:1", "  buy CHEAP pills   at example.com\nnow ");
        passes("u:1", SPAM.toUpperCase());
        assertCode(() -> detector.check("u:1", SPAM), ErrorCode.DUPLICATE_CONTENT_SPAM);
    }

    @Test
    void shortContentIsNotCounted() {
        for (int i = 0; i < 10; i++) {
            passes("u:1", "감사합니다!");
        }
    }

    @Test
    void subjectsAreSeparateAndWindowResets() {
        for (int i = 0; i < 3; i++) {
            passes("u:1", SPAM);
            passes("ip:203.0.113.1", SPAM);
        }
        assertCode(() -> detector.check("u:1", SPAM), ErrorCode.DUPLICATE_CONTENT_SPAM);
        passes("u:2", SPAM);
        nanos.addAndGet(Duration.ofMinutes(10).toNanos());
        passes("u:1", SPAM);
    }

    @Test
    void settingValuesApply() {
        lenient().when(settings.duplicateRule()).thenReturn(new SystemSettingsService.DuplicateRule(1, 2));
        passes("u:1", SPAM);
        passes("u:1", SPAM);
        assertCode(() -> detector.check("u:1", SPAM), ErrorCode.DUPLICATE_CONTENT_SPAM);
        nanos.addAndGet(Duration.ofMinutes(1).toNanos());
        passes("u:1", SPAM);
    }
}
