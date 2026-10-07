package net.java21.blog.backend.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.block.domain.BlogBlock;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.sidebar.domain.BlogSidebarItem;
import net.java21.blog.backend.sidebar.domain.SidebarItemType;
import net.java21.blog.backend.stats.domain.BlogDailyVisit;
import net.java21.blog.backend.subscription.repository.BlogSubscriptionRepository;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 블로그 비우기(T013, 001 FR-159, research B16): 그 블로그의 방명록(답글 먼저)·사이드바·일별 방문·차단 행을 지우고 다른 블로그 행은
 * 남긴다. 쿼리 수는 블로그 수와 무관하다. 트랙백·포털 표는 H2에 없으므로 글 영구 삭제는 {@code PostMySqlBehaviourTest}가 본다.
 */
@JpaRepositoryTest
class TrashPurgeRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private BlogSubscriptionRepository subscriptionRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private QueryCounter queryCounter;

    private TrashPurgeRepository repository;
    private JpaFixtures fx;
    private Blog first;
    private Blog second;
    private Blog third;
    private Blog kept;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        repository = new TrashPurgeRepository(queryFactory, em, subscriptionRepository);
        first = withRows("first");
        second = withRows("second");
        third = withRows("third");
        kept = withRows("kept");
        fx.flushAndClear();
    }

    @Test
    void purgeBlogsRemovesBlogFeatureRowsOfThoseBlogsOnly() {
        assertThat(repository.purgeBlogs(List.of(first.getId()))).isEqualTo(1);

        for (String table : List.of("guestbook_entries", "blog_sidebar_items", "blog_daily_visits", "blog_blocks")) {
            assertThat(count(table, first)).as(table).isZero();
            assertThat(count(table, kept)).as(table).isPositive();
        }
        assertThat(count("guestbook_entries", kept)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT title FROM blogs WHERE id = ?", String.class, first.getId())).isEmpty();
    }

    @Test
    void queryCountDoesNotGrowWithBlogCount() {
        queryCounter.reset();
        repository.purgeBlogs(List.of(first.getId()));
        long one = queryCounter.count();

        queryCounter.reset();
        repository.purgeBlogs(List.of(second.getId(), third.getId()));
        assertThat(queryCounter.count()).isEqualTo(one);
        assertThat(count("guestbook_entries", third)).isZero();
        assertThat(repository.purgeBlogs(List.of())).isZero();
    }

    private Blog withRows(String handle) {
        User owner = fx.user(handle);
        Blog blog = fx.blog(owner, handle);
        User visitor = fx.user(handle + "-visitor");
        GuestbookEntry entry = new GuestbookEntry(blog, visitor, null, "안녕하세요", false);
        em.persist(entry);
        em.persist(new GuestbookEntry(blog, owner, entry, "반가워요", false));
        em.persist(new BlogSidebarItem(blog, SidebarItemType.PROFILE, true, 0));
        em.persist(new BlogDailyVisit(blog, LocalDate.of(2026, 10, 7), 3));
        em.persist(new BlogBlock(blog, visitor, Instant.parse("2026-10-07T00:00:00Z")));
        return blog;
    }

    private long count(String table, Blog blog) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE blog_id = ?", Long.class, blog.getId());
    }
}
