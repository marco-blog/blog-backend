package net.java21.blog.backend.spam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.text.TextNormalizer;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import org.springframework.stereotype.Component;

/**
 * 반복 내용 차단(005 FR-144, research M12). 작성 주체(회원 ID 또는 비회원 IP)와 정규화한 내용(NFKC·소문자·공백 하나)의 SHA-256을 키로
 * 운영 설정 {@code spam.duplicate-comment}의 창 안 횟수를 센다. 이미 {@code maxCount}에 닿았으면 다음 쓰기를 422
 * {@code DUPLICATE_CONTENT_SPAM}으로 거부한다. 같은 글·다른 글, 댓글·방명록을 가리지 않는다. 정규화 후
 * {@code blog.spam.duplicate-comment.min-length}(10)자 미만은 세지 않는다(인사말 보호). 쿼리 0회.
 */
@Component
public class DuplicateContentDetector {

    private final RateLimiter limiter;
    private final SystemSettingsService settings;
    private final SpamProperties properties;

    public DuplicateContentDetector(RateLimiter limiter, SystemSettingsService settings, SpamProperties properties) {
        this.limiter = limiter;
        this.settings = settings;
        this.properties = properties;
    }

    /**
     * 한 번 센다.
     *
     * @param subject 작성 주체 키({@code u:{id}} 또는 {@code ip:{ip}})
     * @throws BusinessException 422 {@code DUPLICATE_CONTENT_SPAM}
     */
    public void check(String subject, String content) {
        String key = TextNormalizer.contentKey(content);
        if (key.codePointCount(0, key.length()) < properties.duplicateComment().minLength()) {
            return;
        }
        SystemSettingsService.DuplicateRule rule = settings.duplicateRule();
        if (!limiter.tryAcquire(RateLimitKind.DUPLICATE_CONTENT, subject + '|' + sha256(key), rule.maxCount(),
                Duration.ofMinutes(rule.windowMinutes()))) {
            throw new BusinessException(ErrorCode.DUPLICATE_CONTENT_SPAM, "Duplicate content from " + subject);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
