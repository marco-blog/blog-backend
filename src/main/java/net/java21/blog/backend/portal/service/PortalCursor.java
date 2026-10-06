package net.java21.blog.backend.portal.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;

/**
 * 최신 글 "더 보기" 커서(003 research P6, 결정 표 10번). 마지막으로 살펴본 행의 발행 시각과 id를 base64url(JSON
 * {@code {"p":"<ISO-8601>","i":<id>}})로 담는다. 발행 시각은 DB 정밀도(마이크로초)를 그대로 담아 경계에서 같은 시각의 글을 id로 잇는다.
 * front에는 불투명한 문자열이다.
 */
public final class PortalCursor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_LENGTH = 200;

    private PortalCursor() {
    }

    /** 커서가 가리키는 행(이 행 다음부터 읽는다). */
    public record Position(Instant publishedAt, Long id) {
    }

    public static String encode(Position position) {
        String json = JSON.writeValueAsString(Map.of("p", position.publishedAt().toString(), "i", position.id()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** @return 커서가 없으면(null·빈 문자열) null */
    public static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            if (cursor.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("too long");
            }
            JsonNode node = JSON.readTree(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            JsonNode p = node.get("p");
            JsonNode i = node.get("i");
            if (p == null || !p.isString() || i == null || !i.isIntegralNumber() || i.asLong() <= 0) {
                throw new IllegalArgumentException("shape");
            }
            return new Position(Instant.parse(p.asString()), i.asLong());
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid cursor",
                List.of(FieldError.of("cursor", "INVALID")));
    }
}
