package net.java21.blog.backend.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 마지막 확인 버전의 조건부 갱신(003 T109): 읽은 값(NULL 포함)일 때만 바뀌므로, 그 사이 다른 요청이 저장한 값을 낮은 값으로 덮어쓰지
 * 않는다.
 */
@JpaRepositoryTest
class LastSeenReleaseVersionRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private UserRepository userRepository;

    @Test
    void conditionalUpdateComparesWithTheValueRead() {
        User user = new JpaFixtures(em).user("마르코");
        em.flush();
        em.clear();

        assertThat(userRepository.findLastSeenReleaseVersion(user.getId())).isEmpty();
        assertThat(userRepository.updateLastSeenReleaseVersion(user.getId(), null, "1.2.0")).isEqualTo(1);
        // 다른 요청이 NULL을 읽고 늦게 도착: 이미 1.2.0이라 바뀌지 않는다
        assertThat(userRepository.updateLastSeenReleaseVersion(user.getId(), null, "1.1.0")).isZero();
        assertThat(userRepository.updateLastSeenReleaseVersion(user.getId(), "1.1.0", "1.1.5")).isZero();
        assertThat(userRepository.findLastSeenReleaseVersion(user.getId())).contains("1.2.0");
        assertThat(userRepository.updateLastSeenReleaseVersion(user.getId(), "1.2.0", "1.10.0")).isEqualTo(1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getLastSeenReleaseVersion())
                .isEqualTo("1.10.0");
    }
}
