package net.java21.blog.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.audit.AdminAuditLogQueryRepository.Criteria;
import net.java21.blog.backend.admin.audit.dto.AuditLogEntryResponse;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 006 T040·T042(FR-106, research A6·A7): 작업 기록 조건(기간·관리자·작업 여러 개·대상), 새 것 먼저, 관리자 닉네임 JOIN 쿼리 2회,
 * 상세 1회(요청 IP 복호화), 1년 정리용 id 찾기·벌크 삭제.
 */
@JpaRepositoryTest
@Import({AdminAuditLogQueryRepository.class, AdminAuditPurgeRepository.class})
class AdminAuditLogQueryRepositoryTest {

    private static final Instant BASE = Instant.parse("2031-03-10T00:00:00Z");
    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Autowired
    private EntityManager em;
    @Autowired
    private AdminAuditLogQueryRepository repository;
    @Autowired
    private AdminAuditPurgeRepository purgeRepository;
    @Autowired
    private QueryCounter queryCounter;

    private User alice;
    private User bob;
    private AdminAuditLog grant;
    private AdminAuditLog limit;
    private AdminAuditLog setting;
    private AdminAuditLog old;

    @BeforeEach
    void setUp() {
        JpaFixtures fx = new JpaFixtures(em);
        alice = fx.user("audit-alice");
        bob = fx.user("audit-bob");
        grant = log(alice, AuditActions.ROLE_GRANT, AuditActions.TARGET_USER, 7L, null, "2031-03-09T10:00:00Z");
        limit = log(bob, AuditActions.USER_BLOG_LIMIT_CHANGE, AuditActions.TARGET_USER, 7L, null,
                "2031-03-09T10:00:00Z");
        setting = log(alice, AuditActions.SETTING_CHANGE, AuditActions.TARGET_SETTING, null, "portal.min-content-length",
                "2031-03-05T00:00:00Z");
        old = log(alice, AuditActions.ROLE_REVOKE, AuditActions.TARGET_USER, 8L, null, "2030-03-01T00:00:00Z");
        em.clear();
    }

    @Test
    void searchByPeriodNewestFirstInTwoQueries() {
        queryCounter.reset();
        Page<AuditLogEntryResponse> page = repository.search(criteria(null, List.of(), null, null, null), PAGE);
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AuditLogEntryResponse::id)
                .containsExactly(limit.getId(), grant.getId(), setting.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
        AuditLogEntryResponse first = page.getContent().get(0);
        assertThat(first.admin().nickname()).isEqualTo("audit-bob");
        assertThat(first.before()).containsEntry("maxBlogs", 1);
        assertThat(first.after()).containsEntry("maxBlogs", 2);
        assertThat(first.targetId()).isEqualTo(7L);
    }

    @Test
    void filters() {
        assertThat(ids(criteria(alice.getId(), List.of(), null, null, null))).containsExactly(grant.getId(),
                setting.getId());
        assertThat(ids(criteria(null, List.of(AuditActions.ROLE_GRANT, AuditActions.SETTING_CHANGE), null, null,
                null))).containsExactly(grant.getId(), setting.getId());
        assertThat(ids(criteria(null, List.of(), AuditActions.TARGET_USER, 7L, null))).containsExactly(limit.getId(),
                grant.getId());
        assertThat(ids(criteria(null, List.of(), null, null, "portal.min-content-length")))
                .containsExactly(setting.getId());
        assertThat(ids(new Criteria(Instant.parse("2030-01-01T00:00:00Z"), BASE, null, null, null, null, null)))
                .contains(old.getId());
    }

    @Test
    void detailFetchesAdminAndDecryptsIp() {
        queryCounter.reset();
        AdminAuditLog found = repository.findWithAdmin(grant.getId()).orElseThrow();
        assertThat(found.getAdmin().getNickname()).isEqualTo("audit-alice");
        assertThat(found.getRequestIp()).isEqualTo("203.0.113.9");
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.findWithAdmin(987654L)).isEmpty();
    }

    @Test
    void purgeFindsOldestFirstAndDeletesByIds() {
        Instant cutoff = Instant.parse("2031-03-06T00:00:00Z");
        List<Long> ids = purgeRepository.findIdsCreatedBefore(cutoff, 10);
        assertThat(ids).containsSubsequence(old.getId(), setting.getId()).doesNotContain(grant.getId());
        assertThat(purgeRepository.findIdsCreatedBefore(cutoff, 1)).hasSize(1);
        assertThat(purgeRepository.deleteByIds(List.of())).isZero();
        assertThat(purgeRepository.deleteByIds(List.of(old.getId(), setting.getId()))).isEqualTo(2);
        assertThat(repository.findWithAdmin(old.getId())).isEmpty();
        assertThat(repository.findWithAdmin(grant.getId())).isPresent();
    }

    private Criteria criteria(Long adminId, List<String> actions, String targetType, Long targetId,
            String targetKey) {
        return new Criteria(Instant.parse("2031-03-03T00:00:00Z"), BASE, adminId, actions, targetType, targetId,
                targetKey);
    }

    private List<Long> ids(Criteria criteria) {
        return repository.search(criteria, PAGE).getContent().stream().map(AuditLogEntryResponse::id).toList();
    }

    private AdminAuditLog log(User admin, String action, String type, Long targetId, String key, String at) {
        AdminAuditLog log = new AdminAuditLog(admin, action, type, targetId, key, Map.of("maxBlogs", 1),
                Map.of("maxBlogs", 2), null, "203.0.113.9");
        em.persist(log);
        em.flush();
        em.createNativeQuery("UPDATE admin_audit_logs SET created_at = :t WHERE id = :id")
                .setParameter("t", Instant.parse(at)).setParameter("id", log.getId()).executeUpdate();
        return log;
    }
}
