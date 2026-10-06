package net.java21.blog.backend.auth.validation;


import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 비밀번호 규칙(FR-003, contracts/api.md): {@value #MIN_LENGTH}~{@value #MAX_LENGTH}자, 영문과 숫자를 모두 포함.
 * 가입·비밀번호 변경·재설정이 같이 쓴다. 어기면 {@code fieldErrors[].code = PASSWORD_WEAK}.
 */
public final class PasswordPolicy {

    public static final String CODE = "PASSWORD_WEAK";
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    private PasswordPolicy() {
    }

    public static boolean isAcceptable(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            letter |= (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            digit |= c >= '0' && c <= '9';
        }
        return letter && digit;
    }

    /** {@link StrongPassword} 검증기. */
    public static class Validator implements ConstraintValidator<StrongPassword, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || isAcceptable(value);
        }
    }
}
