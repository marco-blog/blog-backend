package net.java21.blog.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.AdminProperties;
import net.java21.blog.backend.admin.DatabaseAdminRoleLookup;
import net.java21.blog.backend.admin.SuperAdminBootstrap;
import net.java21.blog.backend.admin.user.AdminUserRepository;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 관리자 저장소(T159·T160·T161): 권한 확인은 쿼리 1회, 블로그 한도·권한 변경은 그 회원 행 하나만, 작업 기록은 JSON 전후 값과
 * 암호화된 요청 IP로 저장되고 다시 읽힌다.
 */
@JpaRepositoryTest
@Import({AdminRepositoriesTest.Beans.class, DatabaseAdminRoleLookup.class, AdminAuditService.class,
        SuperAdminBootstrap.class})
class AdminRepositoriesTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("Boss@Example.com");
        }
    }

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DatabaseAdminRoleLookup roleLookup;
    @Autowired
    private AdminAuditService auditService;
    @Autowired
    private SuperAdminBootstrap bootstrap;
    @Autowired
    private PersonalDataHasher hasher;

    private User admin;
    private User marco;

    @BeforeEach
    void setUp() {
        admin = persistUser("boss@example.com", UserRole.ADMIN, UserStatus.ACTIVE);
        marco = persistUser("marco@example.com", UserRole.USER, UserStatus.ACTIVE);
        em.flush();
        em.clear();
    }

    @Test
    void roleLookupReadsCurrentRoleAndStatusInOneQuery() {
        User suspended = persistUser("sus@example.com", UserRole.SUPER_ADMIN, UserStatus.SUSPENDED);
        User superAdmin = persistUser("root@example.com", UserRole.SUPER_ADMIN, UserStatus.ACTIVE);
        em.flush();
        em.clear();

        queryCounter.reset();
        assertThat(roleLookup.isActiveAdmin(admin.getId())).isTrue();
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(roleLookup.isActiveAdmin(superAdmin.getId())).isTrue();
        assertThat(roleLookup.isActiveAdmin(marco.getId())).isFalse();
        assertThat(roleLookup.isActiveAdmin(suspended.getId())).isFalse();
        assertThat(roleLookup.isActiveAdmin(-1L)).isFalse();

        adminUserRepository.updateRole(admin.getId(), UserRole.USER, NOW);
        assertThat(roleLookup.isActiveAdmin(admin.getId())).isFalse();
    }

    @Test
    void maxBlogsUpdateTouchesOnlyThatMember() {
        queryCounter.reset();
        assertThat(adminUserRepository.updateMaxBlogs(marco.getId(), 0, NOW)).isEqualTo(1);
        assertThat(queryCounter.count()).isEqualTo(1);

        assertThat(userRepository.findById(marco.getId()).orElseThrow().getMaxBlogs()).isZero();
        assertThat(userRepository.findById(marco.getId()).orElseThrow().getUpdatedAt()).isEqualTo(NOW);
        assertThat(userRepository.findById(admin.getId()).orElseThrow().getMaxBlogs()).isNull();

        adminUserRepository.updateMaxBlogs(marco.getId(), null, NOW);
        assertThat(userRepository.findById(marco.getId()).orElseThrow().getMaxBlogs()).isNull();
    }

    @Test
    void auditRecordStoresJsonValuesAndEncryptedIp() {
        Map<String, Object> before = new HashMap<>();
        before.put("maxBlogs", null);

        auditService.record(admin.getId(), "USER_BLOG_LIMIT_CHANGE", "USER", marco.getId(), before,
                Map.of("maxBlogs", 0), "203.0.113.9");
        em.flush();
        em.clear();

        List<AdminAuditLog> logs = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("USER",
                marco.getId());
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.getAdmin().getId()).isEqualTo(admin.getId());
            assertThat(log.getAction()).isEqualTo("USER_BLOG_LIMIT_CHANGE");
            assertThat(log.getBefore()).containsEntry("maxBlogs", null);
            assertThat(log.getAfter()).containsEntry("maxBlogs", 0);
            assertThat(log.getRequestIp()).isEqualTo("203.0.113.9");
            assertThat(log.getTargetKey()).isNull();
            assertThat(log.getReason()).isNull();
            assertThat(log.getCreatedAt()).isNotNull();
            assertThat(log.getId()).isNotNull();
            assertThat(log.getTargetType()).isEqualTo("USER");
            assertThat(log.getTargetId()).isEqualTo(marco.getId());
        });
        byte[] raw = jdbc.queryForObject("select request_ip_enc from admin_audit_logs", byte[].class);
        assertThat(new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("203.0.113.9");
    }

    /** 003 T086·T094: 설정 키처럼 숫자 ID가 없는 대상은 {@code target_key}로, 포털 제외 사유는 {@code reason}에도 남긴다. */
    @Test
    void auditRecordKeyAndReasonAreStored() {
        auditService.recordKey(admin.getId(), AuditActions.SETTING_CHANGE, AuditActions.TARGET_SETTING,
                "portal.min-content-length", Map.of("value", 200), Map.of("value", 500), "::1");
        auditService.record(admin.getId(), AuditActions.PORTAL_EXCLUDE, AuditActions.TARGET_POST, 77L, null,
                Map.of(), Map.of("reason", "광고"), "광고", "::1");
        em.flush();
        em.clear();

        List<AdminAuditLog> logs = em.createQuery("select l from AdminAuditLog l order by l.id", AdminAuditLog.class)
                .getResultList();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getTargetKey()).isEqualTo("portal.min-content-length");
        assertThat(logs.get(0).getTargetId()).isNull();
        assertThat(logs.get(0).getAfter()).containsEntry("value", 500);
        assertThat(logs.get(1).getTargetId()).isEqualTo(77L);
        assertThat(logs.get(1).getReason()).isEqualTo("광고");
    }

    @Test
    void bootstrapPromotesConfiguredMemberOnlyWhileNoSuperAdminExists() {
        assertThat(adminUserRepository.existsByRole(UserRole.SUPER_ADMIN)).isFalse();

        assertThat(bootstrap.bootstrap()).isTrue();

        assertThat(userRepository.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(adminUserRepository.existsByRole(UserRole.SUPER_ADMIN)).isTrue();
        assertThat(bootstrap.bootstrap()).isFalse();
    }

    private User persistUser(String email, UserRole role, UserStatus status) {
        User u = new User(PersonalDataHasher.normalizeEmail(email), hasher.hashEmail(email), "$2a$hash", "닉",
                null, null, "2026-10-06", T0);
        ReflectionTestUtils.setField(u, "role", role);
        ReflectionTestUtils.setField(u, "status", status);
        em.persist(u);
        return u;
    }
}
