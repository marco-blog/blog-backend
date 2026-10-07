package net.java21.blog.backend.block.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.block.domain.BlogBlock;
import net.java21.blog.backend.block.service.BlogBlockPolicy;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.BusinessAssertions;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 차단 확인(T009, 004 FR-146): 차단 행이 있을 때만 true, PK 조회 1회, 비회원은 쿼리 없이 false. */
@JpaRepositoryTest
class BlogBlockRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private BlogBlockRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private BlogBlockPolicy policy;
    private Blog blog;
    private Blog other;
    private User troll;
    private User friend;

    @BeforeEach
    void setUp() {
        JpaFixtures fx = new JpaFixtures(em);
        User owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
        other = fx.blog(fx.user("polo"), "polo");
        troll = fx.user("troll");
        friend = fx.user("friend");
        em.persist(new BlogBlock(blog, troll, Instant.parse("2026-10-07T00:00:00Z")));
        fx.flushAndClear();
        policy = new BlogBlockPolicy(repository);
    }

    @Test
    void blockedOnlyWhereRowExistsWithOneQuery() {
        queryCounter.reset();
        assertThat(policy.isBlocked(blog.getId(), troll.getId())).isTrue();
        assertThat(queryCounter.count()).isEqualTo(1);

        assertThat(policy.isBlocked(blog.getId(), friend.getId())).isFalse();
        assertThat(policy.isBlocked(other.getId(), troll.getId())).isFalse();
    }

    @Test
    void guestsAreNeverBlockedAndCostNoQuery() {
        queryCounter.reset();
        assertThat(policy.isBlocked(blog.getId(), null)).isFalse();
        assertThat(policy.isBlocked(null, troll.getId())).isFalse();
        assertThat(queryCounter.count()).isZero();
    }

    @Test
    void requireNotBlockedHidesTheReason() {
        BusinessAssertions.assertCode(() -> policy.requireNotBlocked(blog.getId(), troll.getId()),
                ErrorCode.FORBIDDEN);
        assertThatCode(() -> policy.requireNotBlocked(blog.getId(), friend.getId())).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireNotBlocked(blog.getId(), null)).doesNotThrowAnyException();
    }
}
