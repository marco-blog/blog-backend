package net.java21.blog.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentUserArgumentResolverTest {

    private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();

    @SuppressWarnings("unused")
    void handler(@CurrentUser AuthUser required, @CurrentUser(required = false) AuthUser optional, AuthUser plain,
            @CurrentUser String wrongType) {
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supportsOnlyAnnotatedAuthUser() throws Exception {
        assertThat(resolver.supportsParameter(param(0))).isTrue();
        assertThat(resolver.supportsParameter(param(1))).isTrue();
        assertThat(resolver.supportsParameter(param(2))).isFalse();
        assertThat(resolver.supportsParameter(param(3))).isFalse();
    }

    @Test
    void anonymousTokenCountsAsNotLoggedIn() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(resolver.resolveArgument(param(1), null, null, null)).isNull();
    }

    @Test
    void otherPrincipalTypeCountsAsNotLoggedIn() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("someone", null, AuthorityUtils.NO_AUTHORITIES));
        assertThat(resolver.resolveArgument(param(1), null, null, null)).isNull();
    }

    @Test
    void returnsPrincipal() throws Exception {
        AuthUser user = new AuthUser(1L, "USER", "f");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, AuthorityUtils.NO_AUTHORITIES));
        assertThat(resolver.resolveArgument(param(0), null, null, null)).isEqualTo(user);
    }

    private MethodParameter param(int index) throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("handler", AuthUser.class, AuthUser.class, AuthUser.class, String.class);
        return new MethodParameter(method, index);
    }
}
