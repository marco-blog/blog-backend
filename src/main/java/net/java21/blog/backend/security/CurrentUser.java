package net.java21.blog.backend.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 메서드 파라미터({@link AuthUser})에 로그인한 회원을 넣는다({@link CurrentUserArgumentResolver}).
 * <ul>
 *   <li>{@code @CurrentUser AuthUser user}: 로그인이 필요하다. 없으면 401 {@code UNAUTHENTICATED}.</li>
 *   <li>{@code @CurrentUser(required = false) AuthUser viewer}: 공개 GET에서 로그인했으면 회원, 아니면 {@code null}.</li>
 * </ul>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {

    boolean required() default true;
}
