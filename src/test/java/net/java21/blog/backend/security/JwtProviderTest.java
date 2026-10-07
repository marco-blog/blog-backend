package net.java21.blog.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import net.java21.blog.backend.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class JwtProviderTest {

    private static final String SECRET = "unit-test-only-jwt-secret-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final String FAMILY = "3f2b8c1e-0000-4000-8000-000000000001";

    private MutableClock clock;
    private JwtProvider provider;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        provider = new JwtProvider(properties(SECRET), clock);
    }

    @Test
    void issuesHs256TokenWithUserIdRoleAndFamily() {
        String token = provider.issue(42L, "USER", FAMILY);

        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"HS256\"");
        assertThat(provider.verify(token)).contains(new AuthUser(42L, "USER", FAMILY));
    }

    @Test
    void expiresAfterAccessTtl() {
        String token = provider.issue(1L, "ADMIN", FAMILY);
        assertThat(provider.accessTtl()).isEqualTo(Duration.ofMinutes(30));

        clock.advance(Duration.ofMinutes(29).plusSeconds(59));
        assertThat(provider.verify(token)).isPresent();

        clock.advance(Duration.ofSeconds(2));
        assertThat(provider.verify(token)).as("만료 시각(초 단위)이 지나면 거부").isEmpty();
    }

    @Test
    void rejectsTamperedToken() {
        String token = provider.issue(1L, "USER", FAMILY);
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"2\",\"role\":\"SUPER_ADMIN\",\"fid\":\"x\",\"exp\":4102444800}".getBytes(StandardCharsets.UTF_8));

        assertThat(provider.verify(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
        assertThat(provider.verify(token.substring(0, token.length() - 2) + "xx")).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithOtherKey() {
        JwtProvider other = new JwtProvider(properties("another-unit-test-secret-0123456789abcdef"), clock);
        assertThat(provider.verify(other.issue(1L, "USER", FAMILY))).isEmpty();
    }

    @Test
    void rejectsUnsignedAndGarbageTokens() {
        String unsigned = Jwts.builder().subject("1").claim("role", "USER").claim("fid", FAMILY)
                .expiration(Date.from(NOW.plusSeconds(60))).compact();
        assertThat(provider.verify(unsigned)).isEmpty();
        assertThat(provider.verify("not-a-jwt")).isEmpty();
        assertThat(provider.verify("")).isEmpty();
        assertThat(provider.verify(null)).isEmpty();
    }

    @Test
    void rejectsTokenWithoutRequiredClaims() {
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date exp = Date.from(NOW.plusSeconds(60));
        String noRole = Jwts.builder().subject("1").claim("fid", FAMILY).expiration(exp).signWith(key).compact();
        String badSubject = Jwts.builder().subject("abc").claim("role", "USER").claim("fid", FAMILY)
                .expiration(exp).signWith(key).compact();
        String noExpiry = Jwts.builder().subject("1").claim("role", "USER").claim("fid", FAMILY)
                .signWith(key).compact();

        assertThat(provider.verify(noRole)).isEmpty();
        assertThat(provider.verify(badSubject)).isEmpty();
        assertThat(provider.verify(noExpiry)).isEmpty();
    }

    /** 004 보호 글 열람 쿠키(research B4): 같은 키, 다른 종류. 접근 토큰과 서로 바꿔 쓸 수 없다. */
    @Test
    void scopedTokenCarriesClaimsAndExpires() {
        String token = provider.issueScoped("post-unlock", Map.of("pid", 12L, "pwf", "abcd"), Duration.ofMinutes(30));

        assertThat(provider.verifyScoped(token, "post-unlock")).hasValueSatisfying(claims -> {
            assertThat(((Number) claims.get("pid")).longValue()).isEqualTo(12L);
            assertThat(claims.get("pwf")).isEqualTo("abcd");
        });
        assertThat(provider.verifyScoped(token, "other")).as("종류가 다르면 거부").isEmpty();
        assertThat(provider.verify(token)).as("접근 토큰으로 쓸 수 없다").isEmpty();

        clock.advance(Duration.ofMinutes(30).plusSeconds(1));
        assertThat(provider.verifyScoped(token, "post-unlock")).isEmpty();
    }

    @Test
    void accessTokenIsNotAScopedToken() {
        String access = provider.issue(1L, "USER", FAMILY);
        assertThat(provider.verifyScoped(access, "post-unlock")).isEmpty();
        assertThat(provider.verifyScoped(null, "post-unlock")).isEmpty();
        assertThat(provider.verifyScoped(" ", "post-unlock")).isEmpty();
        assertThat(provider.verifyScoped("not.a.token", "post-unlock")).isEmpty();

        String foreign = new JwtProvider(properties("another-unit-test-only-secret-0123456789abcdef"), clock)
                .issueScoped("post-unlock", Map.of("pid", 1L), Duration.ofMinutes(30));
        assertThat(provider.verifyScoped(foreign, "post-unlock")).as("다른 키로 서명").isEmpty();
    }

    @Nested
    class Properties {

        @Configuration(proxyBeanMethods = false)
        @EnableConfigurationProperties(AuthProperties.class)
        static class Bind {
        }

        private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Bind.class);

        @Test
        void bindsDefaults() {
            runner.withPropertyValues("blog.auth.jwt-secret=" + SECRET).run(context -> {
                AuthProperties p = context.getBean(AuthProperties.class);
                assertThat(p.accessTtl()).isEqualTo(Duration.ofMinutes(30));
                assertThat(p.refreshIdleTtl()).isEqualTo(Duration.ofHours(4));
                assertThat(p.refreshAbsoluteTtl()).isEqualTo(Duration.ofDays(7));
                assertThat(p.refreshReuseGrace()).isEqualTo(Duration.ofSeconds(10));
                assertThat(p.loginMaxFailures()).isEqualTo(5);
                assertThat(p.loginLockDuration()).isEqualTo(Duration.ofMinutes(10));
            });
        }

        @Test
        void failsToStartWhenSecretIsShorterThan32Bytes() {
            runner.withPropertyValues("blog.auth.jwt-secret=" + "x".repeat(31)).run(context ->
                    assertThat(context).hasFailed().getFailure().hasStackTraceContaining("32 bytes"));
        }

        @Test
        void failsToStartWhenSecretIsMissing() {
            runner.run(context ->
                    assertThat(context).hasFailed().getFailure().hasStackTraceContaining("blog.auth.jwt-secret"));
        }

        @Test
        void acceptsExactly32Bytes() {
            runner.withPropertyValues("blog.auth.jwt-secret=" + "x".repeat(32)).run(context ->
                    assertThat(context).hasNotFailed());
        }
    }

    static AuthProperties properties(String secret) {
        return new AuthProperties(Duration.ofMinutes(30), Duration.ofHours(4), Duration.ofDays(7),
                Duration.ofSeconds(10), secret, 5, Duration.ofMinutes(10));
    }
}
