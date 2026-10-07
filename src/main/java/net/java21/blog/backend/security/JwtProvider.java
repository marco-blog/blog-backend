package net.java21.blog.backend.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 접근 토큰(JWT, HS256, jjwt 0.13) 발급·검증(research R2).
 * 클레임: {@code sub}=userId, {@code role}, {@code fid}=로그인 계열 ID, {@code iat}, {@code exp}(발급 + {@code blog.auth.access-ttl}).
 * 시각은 주입받은 {@link Clock}으로 정해 테스트에서 만료를 조절할 수 있다.
 */
public class JwtProvider {

    static final String ROLE = "role";
    static final String FAMILY_ID = "fid";
    /** 접근 토큰이 아닌 짧은 서명 토큰(004 보호 글 열람 쿠키 등)의 종류 클레임. 접근 토큰에는 없으므로 서로 바꿔 쓸 수 없다. */
    static final String TYPE = "typ";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Clock clock;
    private final JwtParser parser;

    public JwtProvider(AuthProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = properties.accessTtl();
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public String issue(long userId, String role, String familyId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claim(ROLE, role)
                .claim(FAMILY_ID, familyId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 서명·만료·필수 클레임이 맞으면 회원, 아니면(변조, 만료, 서명 없음, 형식 오류) 빈 값. 이유는 구분하지 않는다. */
    public Optional<AuthUser> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            String role = claims.get(ROLE, String.class);
            String familyId = claims.get(FAMILY_ID, String.class);
            if (claims.getExpiration() == null || claims.getSubject() == null || isBlank(role) || isBlank(familyId)) {
                return Optional.empty();
            }
            return Optional.of(new AuthUser(Long.parseLong(claims.getSubject()), role, familyId));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 접근 토큰이 아닌 짧은 서명 토큰(004 research B4: 보호 글 열람 쿠키). 같은 키로 서명하되 {@code typ} 클레임으로 종류를 나누고
     * {@code role}·{@code fid}가 없으므로 {@link #verify}를 통과하지 못한다.
     */
    public String issueScoped(String type, Map<String, ?> claims, Duration ttl) {
        Instant now = clock.instant();
        return Jwts.builder()
                .claims(claims)
                .claim(TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** {@link #issueScoped}로 만든 {@code type} 토큰이면(서명·만료 확인) 클레임, 아니면 빈 값. */
    public Optional<Map<String, Object>> verifyScoped(String token, String type) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            if (claims.getExpiration() == null || !type.equals(claims.get(TYPE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(Map.copyOf(claims));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
