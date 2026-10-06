package net.java21.blog.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.auth.domain.RefreshToken;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 리프레시 토큰 조회와 일괄 폐기(T053). */
@JpaRepositoryTest
class RefreshTokenRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private RefreshTokenRepository repository;
    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;

    private User marco;
    private User other;

    @BeforeEach
    void setUp() {
        marco = persistUser("marco@example.com", "a".repeat(64));
        other = persistUser("other@example.com", "b".repeat(64));
        token(marco, "family-a", "1");
        token(marco, "family-a", "2");
        token(marco, "family-b", "3");
        token(other, "family-c", "4");
        em.flush();
        em.clear();
    }

    @Test
    void findByTokenHashReadsTokenAndMemberInOneQuery() {
        queryCounter.reset();
        RefreshToken token = repository.findByTokenHash(hash("2")).orElseThrow();
        assertThat(Hibernate.isInitialized(token.getUser())).isTrue();
        assertThat(token.getUser().getNickname()).isEqualTo("marco@example.com");
        assertThat(token.getFamilyId()).isEqualTo("family-a");
        assertThat(queryCounter.count()).isEqualTo(1);

        assertThat(repository.findByTokenHash(hash("9"))).isEmpty();
        assertThat(repository.findByTokenHashForUpdate(hash("3")).orElseThrow().getFamilyId()).isEqualTo("family-b");
    }

    @Test
    void revokeFamilyIsOneUpdateAndTouchesOnlyThatFamily() {
        queryCounter.reset();
        int updated = repository.revokeFamily("family-a", NOW);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(updated).isEqualTo(2);

        assertThat(revokedAt("1")).isEqualTo(NOW);
        assertThat(revokedAt("2")).isEqualTo(NOW);
        assertThat(revokedAt("3")).isNull();
        assertThat(revokedAt("4")).isNull();

        // 이미 폐기된 행의 시각은 바꾸지 않는다.
        assertThat(repository.revokeFamily("family-a", NOW.plusSeconds(60))).isZero();
    }

    @Test
    void revokeAllByUserIdIsOneUpdate() {
        queryCounter.reset();
        int updated = repository.revokeAllByUserId(marco.getId(), NOW);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(updated).isEqualTo(3);
        assertThat(revokedAt("4")).isNull();
    }

    /** 비밀번호 변경(T122): 현재 기기의 계열만 남기고 그 회원의 나머지 계열을 한 번에 폐기한다. */
    @Test
    void revokeAllByUserIdExceptFamilyKeepsCurrentDevice() {
        queryCounter.reset();
        int updated = repository.revokeAllByUserIdExceptFamily(marco.getId(), "family-b", NOW);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(updated).isEqualTo(2);

        assertThat(revokedAt("1")).isEqualTo(NOW);
        assertThat(revokedAt("2")).isEqualTo(NOW);
        assertThat(revokedAt("3")).isNull();
        assertThat(revokedAt("4")).isNull();
    }

    private Instant revokedAt(String suffix) {
        return repository.findByTokenHash(hash(suffix)).orElseThrow().getRevokedAt();
    }

    private User persistUser(String email, String emailHash) {
        User user = new User(email, emailHash, "$2a$hash", email, null, null, "2026-10-06", NOW);
        em.persist(user);
        return user;
    }

    private void token(User user, String familyId, String suffix) {
        em.persist(RefreshToken.first(user, familyId, hash(suffix), NOW, Duration.ofHours(4), Duration.ofDays(7)));
    }

    private static String hash(String suffix) {
        return "0".repeat(63) + suffix;
    }
}
