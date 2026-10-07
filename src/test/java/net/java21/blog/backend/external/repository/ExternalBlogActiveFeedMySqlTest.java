package net.java21.blog.backend.external.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 007 T013: 생성 컬럼 {@code active_feed_hash} UNIQUE(FR-112, research E6). 거절·해제되지 않은 등록은 피드당 하나. MySQL에서만 확인된다.
 */
@MySqlRepositoryTest
class ExternalBlogActiveFeedMySqlTest {

    private static final String FEED = "https://same.example/feed";

    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private ExternalFixtures x;
    private User a;
    private User b;
    private Topic topic;

    @BeforeEach
    void setUp() {
        JpaFixtures f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        a = f.user("feed-a");
        b = f.user("feed-b");
        topic = f.topic(f.topic(null, "feed-parent", 1), "feed-child", 1);
    }

    @Test
    void pendingAndActiveOfSameFeedConflict() {
        x.blog(a, FEED, topic, ExternalBlogStatus.ACTIVE);
        em.flush();
        assertThatThrownBy(() -> {
            x.blog(b, FEED, topic, ExternalBlogStatus.PENDING);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectedOrReleasedDoNotHoldTheFeed() {
        x.blog(a, FEED, topic, ExternalBlogStatus.REJECTED);
        x.blog(b, FEED, topic, ExternalBlogStatus.RELEASED);
        ExternalBlog active = x.blog(null, FEED, topic, ExternalBlogStatus.ACTIVE);
        em.flush();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_blogs WHERE active_feed_hash IS NOT NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_feed_hash FROM external_blogs WHERE id = ?", String.class,
                active.getId())).isEqualTo(active.getFeedUrlHash());
    }

    @Test
    void reactivatingReleasedRowRecomputesAndConflicts() {
        ExternalBlog released = x.blog(a, FEED, topic, ExternalBlogStatus.RELEASED);
        x.blog(b, FEED, topic, ExternalBlogStatus.ACTIVE);
        em.flush();

        assertThatThrownBy(() -> jdbc.update("UPDATE external_blogs SET status = 'ACTIVE' WHERE id = ?",
                released.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
