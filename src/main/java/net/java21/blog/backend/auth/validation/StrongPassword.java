package net.java21.blog.backend.auth.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import net.java21.blog.backend.common.error.FieldErrorCode;

/** 비밀번호 규칙({@link PasswordPolicy}). 어기면 {@code fieldErrors[].code = PASSWORD_WEAK}. null은 통과(@NotNull과 함께 쓴다). */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = PasswordPolicy.Validator.class)
@FieldErrorCode(PasswordPolicy.CODE)
public @interface StrongPassword {

    String message() default "password must be 8-64 characters with letters and digits";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
