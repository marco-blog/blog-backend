package net.java21.blog.backend.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 비밀번호 규칙: 8~64자, 영문+숫자(FR-003). */
class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"abcdefg1", "Password123", "1234567a", "a1!@#$%^&*", "한글도OK1234"})
    void accepts(String password) {
        assertThat(PasswordPolicy.isAcceptable(password)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc123", "abcdefgh", "12345678", "!!!!!!!!", "한글비밀번호입니다1"})
    void rejects(String password) {
        assertThat(PasswordPolicy.isAcceptable(password)).isFalse();
    }

    @Test
    void lengthBounds() {
        assertThat(PasswordPolicy.isAcceptable("a1" + "x".repeat(62))).isTrue();
        assertThat(PasswordPolicy.isAcceptable("a1" + "x".repeat(63))).isFalse();
        assertThat(PasswordPolicy.isAcceptable("a1xxxxx")).isFalse();
        assertThat(PasswordPolicy.isAcceptable(null)).isFalse();
    }

    @Test
    void validatorLetsNullThrough() {
        PasswordPolicy.Validator validator = new PasswordPolicy.Validator();
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("short1", null)).isFalse();
        assertThat(validator.isValid("longenough1", null)).isTrue();
    }
}
