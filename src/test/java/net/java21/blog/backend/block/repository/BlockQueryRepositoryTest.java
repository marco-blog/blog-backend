package net.java21.blog.backend.block.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.block.domain.BlogBlock;
import net.java21.blog.backend.block.repository.BlockQueryRepository.BlockRow;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** 차단 목록(T113, 004 FR-146): 최신순, 닉네임·프로필 키 포함, 다른 블로그 차단 제외, 페이지 크기와 무관하게 쿼리 2회. */
@JpaRepositoryTest
class BlockQueryRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private QueryCounter queryCounter;

    private BlockQueryRepository repository;
    private JpaFixtures fx;
    private Blog blog;

    @BeforeEach
    void setUp() {
        repository = new BlockQueryRepository(queryFactory);
        fx = new JpaFixtures(em);
        blog = fx.blog(fx.user("marco"), "marco");
    }

    @Test
    void newestFirstWithProfileAndOnlyThisBlogInTwoQueries() {
        User troll = fx.user("troll");
        Media profile = new Media(troll, "profileTroll0000000000", MediaPurpose.PROFILE, "p.png", "2026/10/p.png",
                "image/png", 10, 1, 1);
        TestEntities.with(profile, "status", MediaStatus.ATTACHED);
        em.persist(profile);
        troll.changeProfileMedia(profile);
        User spammer = fx.user("spammer");
        Blog other = fx.blog(fx.user("polo"), "polo");
        for (int i = 0; i < 5; i++) {
            em.persist(new BlogBlock(blog, fx.user("extra" + i), T0.minusSeconds(100 + i)));
        }
        em.persist(new BlogBlock(blog, troll, T0));
        em.persist(new BlogBlock(blog, spammer, T0.plusSeconds(60)));
        em.persist(new BlogBlock(other, troll, T0.plusSeconds(120)));
        fx.flushAndClear();

        queryCounter.reset();
        Page<BlockRow> page = repository.findPage(blog.getId(), PageRequest.of(0, 3));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(7);
        assertThat(page.getContent()).extracting(BlockRow::nickname).containsExactly("spammer", "troll", "extra0");
        assertThat(page.getContent().get(1)).isEqualTo(
                new BlockRow(troll.getId(), "troll", "profileTroll0000000000", T0));
        assertThat(page.getContent().getFirst().profileMediaKey()).isNull();
    }

    @Test
    void emptyWhenNothingBlocked() {
        fx.flushAndClear();
        Page<BlockRow> page = repository.findPage(blog.getId(), PageRequest.of(0, 20));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }
}
