package net.java21.blog.backend.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.search.SearchProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 검색어 해석(T059, research D4): 앞뒤 공백 제거 후 2~100자, 공백 분리·2자 미만 낱말 버림·최대 5개, 남는 낱말이 없으면 {@code TOO_SHORT},
 * BOOLEAN MODE 연산자 문자 제거, 결과는 {@code +"스프링" +"부트"}.
 */
class SearchQueryParserTest {

    private final SearchQueryParser parser = new SearchQueryParser(new SearchProperties(5, 2));

    @Test
    void wrapsEachTermAsRequiredPhrase() {
        SearchQuery query = parser.parse("  스프링   부트 ");

        assertThat(query.terms()).containsExactly("스프링", "부트");
        assertThat(query.booleanQuery()).isEqualTo("+\"스프링\" +\"부트\"");
    }

    @Test
    void dropsShortTermsAndKeepsAtMostFive() {
        SearchQuery query = parser.parse("a 자바 b 스프링 부트 jpa 도커 쿠버 네티스");

        assertThat(query.terms()).containsExactly("자바", "스프링", "부트", "jpa", "도커");
    }

    @Test
    void removesBooleanOperatorCharacters() {
        SearchQuery query = parser.parse("+스프링* -\"부트\" (jpa) <a>b ~c@d");

        assertThat(query.terms()).containsExactly("스프링", "부트", "jpa", "ab", "cd");
        assertThat(query.booleanQuery()).doesNotContain("*", "-", "(", ")", "<", ">", "~", "@")
                .isEqualTo("+\"스프링\" +\"부트\" +\"jpa\" +\"ab\" +\"cd\"");
    }

    @Test
    void duplicateTermsAreUsedOnce() {
        assertThat(parser.parse("스프링 스프링 부트").terms()).containsExactly("스프링", "부트");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingQueryIsRequired(String q) {
        assertInvalid(q, "REQUIRED", Map.of());
    }

    @Test
    void oneCharacterIsTooShort() {
        assertInvalid(" 스 ", "TOO_SHORT", Map.of("min", 2));
    }

    @Test
    void moreThanHundredCharactersIsTooLong() {
        assertThat(parser.parse("가".repeat(100)).terms()).hasSize(1);
        assertInvalid("가".repeat(101), "TOO_LONG", Map.of("max", 100));
    }

    @Test
    void lengthCountsCharactersNotUtf16Units() {
        // 이모지(서로게이트 쌍) 50개는 100 UTF-16 단위지만 50자다.
        assertThat(parser.parse("😀".repeat(50) + " 스프링").terms()).contains("스프링");
    }

    @Test
    void noUsableTermIsTooShort() {
        assertInvalid("a b c d", "TOO_SHORT", Map.of("min", 2));
        assertInvalid("+* -\"", "TOO_SHORT", Map.of("min", 2));
        assertInvalid("+a +b", "TOO_SHORT", Map.of("min", 2));
    }

    private void assertInvalid(String q, String code, Map<String, Object> params) {
        assertThatThrownBy(() -> parser.parse(q))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).containsExactly(new FieldError("q", code, params));
                });
    }
}
