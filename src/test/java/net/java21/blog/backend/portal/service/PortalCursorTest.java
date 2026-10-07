package net.java21.blog.backend.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 최신 글 커서(003 T036, research P6): base64url 왕복, 잘못된 커서는 400 {@code VALIDATION_FAILED}(cursor, INVALID). */
class PortalCursorTest {

    @Test
    void roundTripsKeepingMicrosecondPrecision() {
        PortalCursor.Position position = new PortalCursor.Position(Instant.parse("2026-10-06T01:24:19.123456Z"), 123L);

        String cursor = PortalCursor.encode(position);

        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        assertThat(PortalCursor.decode(cursor)).isEqualTo(position);
    }

    @Test
    void sourceRoundTripsAndOldCursorIsInternal() {
        PortalCursor.Position external = new PortalCursor.Position(Instant.parse("2026-10-06T01:24:19Z"), 7L,
                PortalSourceType.EXTERNAL);
        assertThat(PortalCursor.decode(PortalCursor.encode(external))).isEqualTo(external);
        // 007 이전 커서({"p","i"}만)는 내부 글 위치로 읽는다
        String old = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"p\":\"2026-10-06T01:24:19Z\",\"i\":5}".getBytes(StandardCharsets.UTF_8));
        assertThat(PortalCursor.decode(old).source()).isEqualTo(PortalSourceType.INTERNAL);
        assertThat(new PortalCursor.Position(Instant.EPOCH, 1L, null).source()).isEqualTo(PortalSourceType.INTERNAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"X\"", "1", "null", "\"\""})
    void unknownSourceIsRejected(String source) {
        String json = "{\"p\":\"2026-10-06T01:24:19Z\",\"i\":5,\"s\":" + source + "}";
        assertInvalid(Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingCursorMeansFirstBatch() {
        assertThat(PortalCursor.decode(null)).isNull();
        assertThat(PortalCursor.decode("")).isNull();
        assertThat(PortalCursor.decode("  ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64!", "e30", "eyJwIjoxLCJpIjoxfQ", "eyJwIjoieCIsImkiOjF9",
            "eyJwIjoiMjAyNi0xMC0wNlQwMToyNDoxOVoiLCJpIjowfQ", "eyJwIjoiMjAyNi0xMC0wNlQwMToyNDoxOVoiLCJpIjoiMSJ9"})
    void malformedCursorIsValidationFailedOnTheCursorField(String cursor) {
        assertInvalid(cursor);
    }

    @Test
    void tooLongCursorIsRejected() {
        assertInvalid(Base64.getUrlEncoder().encodeToString("x".repeat(300).getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertInvalid(String cursor) {
        assertThatThrownBy(() -> PortalCursor.decode(cursor))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).containsExactly(FieldError.of("cursor", "INVALID"));
                });
    }
}
