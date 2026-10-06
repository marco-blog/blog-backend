package net.java21.blog.backend.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.LoginHistory;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** 로그인 기록 조회와 정리(T125, FR-139): 회원별 최신순 페이지(DTO projection, 쿼리 수 고정), 보관 기간이 지난 기록 일괄 삭제. */
@JpaRepositoryTest
@Import(LoginHistoryQueryRepository.class)
class LoginHistoryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private LoginHistoryRepository repository;
    @Autowired
    private LoginHistoryQueryRepository queryRepository;
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
    }

    private LoginHistory history(User user, boolean success, String ip, Instant at) {
        return repository.save(new LoginHistory(user, success, ip, "UA " + at, at));
    }

    @Test
    void pageOfOneMemberNewestFirstWithFixedQueryCount() {
        for (int i = 0; i < 5; i++) {
            history(marco, i % 2 == 0, "211.234.56." + i, NOW.minus(Duration.ofHours(i)));
        }
        history(other, true, "10.0.0.1", NOW.plusSeconds(1));
        history(null, false, "10.0.0.2", NOW.plusSeconds(2));
        em.flush();
        em.clear();

        queryCounter.reset();
        Page<LoginHistoryRow> first = queryRepository.findByUserId(marco.getId(), PageRequest.of(0, 2));
        assertThat(queryCounter.count()).isEqualTo(2);

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getContent()).extracting(LoginHistoryRow::at)
                .containsExactly(NOW, NOW.minus(Duration.ofHours(1)));
        assertThat(first.getContent().get(0)).isEqualTo(
                new LoginHistoryRow(NOW, true, "211.234.56.0", "UA " + NOW));
        assertThat(first.getContent().get(1).success()).isFalse();

        Page<LoginHistoryRow> last = queryRepository.findByUserId(marco.getId(), PageRequest.of(2, 2));
        assertThat(last.getContent()).extracting(LoginHistoryRow::ip).containsExactly("211.234.56.4");
    }

    @Test
    void sameTimeIsOrderedByIdDescending() {
        history(marco, false, "10.0.0.1", NOW);
        history(marco, true, "10.0.0.2", NOW);
        em.flush();
        em.clear();

        assertThat(queryRepository.findByUserId(marco.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(LoginHistoryRow::ip).containsExactly("10.0.0.2", "10.0.0.1");
    }

    @Test
    void emptyPageStillHasTotal() {
        assertThat(queryRepository.findByUserId(marco.getId(), PageRequest.of(0, 20)).getTotalElements()).isZero();
    }

    @Test
    void deletesRecordsOlderThanCutoffInBatches() {
        Instant cutoff = NOW.minus(Duration.ofDays(90));
        LoginHistory old1 = history(marco, true, "10.0.0.1", cutoff.minusSeconds(1));
        LoginHistory old2 = history(null, false, "10.0.0.2", cutoff.minus(Duration.ofDays(10)));
        LoginHistory old3 = history(other, true, "10.0.0.3", cutoff.minus(Duration.ofDays(1)));
        LoginHistory kept = history(marco, true, "10.0.0.4", cutoff);
        em.flush();
        em.clear();

        List<Long> batch = queryRepository.findIdsCreatedBefore(cutoff, 2);
        assertThat(batch).containsExactly(old2.getId(), old3.getId());
        assertThat(queryRepository.deleteByIds(batch)).isEqualTo(2);
        assertThat(queryRepository.deleteByIds(List.of())).isZero();
        assertThat(queryRepository.findIdsCreatedBefore(cutoff, 2)).containsExactly(old1.getId());

        assertThat(repository.findAll()).extracting(LoginHistory::getId).containsExactly(old1.getId(), kept.getId());
    }

    private User persistUser(String email, String emailHash) {
        User user = new User(email, emailHash, "$2a$hash", email, null, null, "2026-10-06", NOW);
        em.persist(user);
        return user;
    }
}
