package net.java21.blog.backend.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.LoginHistory;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.LoginHistoryRepository;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 저장된 개인정보에 평문이 없다(T126, SC-022, quickstart #22): 가입·로그인 기록을 저장한 뒤 {@code users.email_enc}·
 * {@code login_history.ip_enc}의 원시 값(바이트)에 평문이 없고, 이메일은 {@code email_hash}로 찾는다.
 */
@JpaRepositoryTest
class PersonalDataAtRestTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final String EMAIL = "marco@example.com";
    private static final String IP = "211.234.56.78";

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LoginHistoryRepository loginHistoryRepository;
    @Autowired
    private PersonalDataHasher hasher;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void emailAndIpAreNotStoredInPlainText() {
        User user = userRepository.save(new User(EMAIL, hasher.hashEmail(EMAIL), "$2a$hash", "마르코", "ko", null,
                "2026-10-06", NOW));
        LoginHistory history = loginHistoryRepository.save(new LoginHistory(user, true, IP, "Mozilla/5.0", NOW));
        loginHistoryRepository.save(new LoginHistory(null, false, IP, null, NOW));
        em.flush();
        em.clear();

        byte[] emailEnc = jdbc.queryForObject("SELECT email_enc FROM users WHERE id = ?", byte[].class, user.getId());
        String emailHash = jdbc.queryForObject("SELECT email_hash FROM users WHERE id = ?", String.class,
                user.getId());
        assertNoPlainText(emailEnc, EMAIL);
        assertThat(emailHash).isEqualTo(TestEntities.HASHER.hashEmail(EMAIL)).doesNotContain("marco");

        for (byte[] ipEnc : jdbc.queryForList("SELECT ip_enc FROM login_history", byte[].class)) {
            assertNoPlainText(ipEnc, IP);
        }

        User found = userRepository.findByEmailHash(hasher.hashEmail("  MARCO@Example.com ")).orElseThrow();
        assertThat(found.getId()).isEqualTo(user.getId());
        assertThat(found.getEmail()).isEqualTo(EMAIL);
        assertThat(loginHistoryRepository.findById(history.getId()).orElseThrow().getIp()).isEqualTo(IP);
    }

    private static void assertNoPlainText(byte[] stored, String plain) {
        assertThat(stored).isNotEmpty();
        String asText = new String(stored, StandardCharsets.ISO_8859_1);
        assertThat(asText).doesNotContain(plain);
        assertThat(HexFormat.of().formatHex(stored))
                .doesNotContain(HexFormat.of().formatHex(plain.getBytes(StandardCharsets.UTF_8)));
    }
}
