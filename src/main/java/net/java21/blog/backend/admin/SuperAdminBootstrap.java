package net.java21.blog.backend.admin;

import java.time.Clock;

import net.java21.blog.backend.admin.user.AdminUserRepository;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 첫 최고 관리자 지정(T161, 006 FR-105). 기동 시 SUPER_ADMIN이 한 명도 없고 {@code blog.admin.bootstrap-super-admin-email}이
 * 있으면 그 이메일(정규화 후 HMAC 해시로 조회, FR-135)의 정상 회원을 SUPER_ADMIN으로 바꾼다. 이미 SUPER_ADMIN이 있으면
 * 아무것도 하지 않는다. 로그에는 이메일을 남기지 않는다.
 */
@Component
public class SuperAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final AdminProperties properties;
    private final AdminUserRepository adminUserRepository;
    private final UserRepository userRepository;
    private final PersonalDataHasher hasher;
    private final Clock clock;

    public SuperAdminBootstrap(AdminProperties properties, AdminUserRepository adminUserRepository,
            UserRepository userRepository, PersonalDataHasher hasher, Clock clock) {
        this.properties = properties;
        this.adminUserRepository = adminUserRepository;
        this.userRepository = userRepository;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** @return SUPER_ADMIN을 새로 지정했으면 true */
    @Transactional
    public boolean bootstrap() {
        if (!properties.hasBootstrapEmail() || adminUserRepository.existsByRole(UserRole.SUPER_ADMIN)) {
            return false;
        }
        return userRepository.findByEmailHash(hasher.hashEmail(properties.bootstrapSuperAdminEmail()))
                .filter(user -> user.isActive())
                .map(user -> {
                    adminUserRepository.updateRole(user.getId(), UserRole.SUPER_ADMIN, clock.instant());
                    log.info("Bootstrap: user {} is now SUPER_ADMIN", user.getId());
                    return true;
                })
                .orElseGet(() -> {
                    log.warn("Bootstrap: no active member for blog.admin.bootstrap-super-admin-email; "
                            + "SUPER_ADMIN not assigned");
                    return false;
                });
    }
}
