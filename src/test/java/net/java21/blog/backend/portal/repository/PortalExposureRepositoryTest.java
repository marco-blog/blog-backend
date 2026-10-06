package net.java21.blog.backend.portal.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 포털 노출 조각(T006, FR-088, research P1, SC-004): 001 노출 매트릭스 행 전부와 FR-088 조건 다섯 가지를 거르고, 자바 판단
 * {@link PortalExposure#evaluate}가 같은 픽스처에서 쿼리와 같은 결과·이유를 내며, 조회는 쿼리 1회다.
 */
@JpaRepositoryTest
class PortalExposureRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final Duration DELAY = Duration.ofHours(24);
    private static final PortalCriteria CRITERIA = new PortalCriteria(NOW, DELAY, 200);
    private static final String LONG = "가".repeat(200);

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private QueryCounter queryCounter;

    @Test
    void portalVisibleMatchesEvaluateForEveryMatrixRowAndPortalCondition() {
        JpaFixtures fx = new JpaFixtures(em);
        User owner = fx.user("owner");
        Blog main = fx.blog(owner, "owner");
        Map<String, Post> posts = new LinkedHashMap<>();
        posts.put("normal", fx.publishedText(main, "normal", LONG, null, NOW.minusSeconds(60)));
        posts.put("exactly200", fx.publishedText(main, "exactly200", "가".repeat(200), null, NOW.minusSeconds(60)));
        posts.put("private", fx.publishedText(main, "private", LONG, null, PostStatus.PUBLISHED, PostVisibility.PRIVATE,
                NOW.minusSeconds(60)));
        posts.put("draft", fx.publishedText(main, "draft", LONG, null, PostStatus.DRAFT, PostVisibility.PUBLIC,
                NOW.minusSeconds(60)));
        posts.put("deleted", fx.publishedText(main, "deleted", LONG, null, PostStatus.DELETED, PostVisibility.PUBLIC,
                NOW.minusSeconds(60)));
        posts.put("short199", fx.publishedText(main, "short199", "가".repeat(199), null, NOW.minusSeconds(60)));
        Post excluded = fx.publishedText(main, "excluded", LONG, null, NOW.minusSeconds(60));
        posts.put("excluded", excluded);
        em.persist(new PortalExclusion(excluded, "광고", owner));

        Blog deletedBlog = fx.blog(owner, "gone");
        posts.put("deletedBlog", fx.publishedText(deletedBlog, "deletedBlog", LONG, null, NOW.minusSeconds(60)));
        deletedBlog.delete(NOW);

        Blog portalOff = fx.blog(owner, "off");
        portalOff.changePortalSettings(false, null);
        posts.put("portalOff", fx.publishedText(portalOff, "portalOff", LONG, null, NOW.minusSeconds(60)));

        User suspended = fx.user("suspended");
        posts.put("suspended", fx.publishedText(fx.blog(suspended, "suspended"), "suspended", LONG, null,
                NOW.minusSeconds(60)));
        User withdrawn = fx.user("withdrawn");
        posts.put("withdrawn", fx.publishedText(fx.blog(withdrawn, "withdrawn"), "withdrawn", LONG, null,
                NOW.minusSeconds(60)));
        withdrawn.withdraw(NOW);

        User boundary = fx.user("boundary");
        posts.put("joinedExactlyAtDelay", fx.publishedText(fx.blog(boundary, "boundary"), "boundary", LONG, null,
                NOW.minusSeconds(60)));
        User fresh = fx.user("fresh");
        posts.put("newMember", fx.publishedText(fx.blog(fresh, "fresh"), "newMember", LONG, null, NOW.minusSeconds(60)));

        for (User u : List.of(owner, suspended, withdrawn)) {
            fx.joinedAt(u, NOW.minus(Duration.ofDays(30)));
        }
        fx.joinedAt(boundary, NOW.minus(DELAY));
        fx.joinedAt(fresh, NOW.minus(DELAY).plusSeconds(1));
        em.createNativeQuery("UPDATE users SET status = 'SUSPENDED' WHERE id = :id")
                .setParameter("id", suspended.getId()).executeUpdate();
        fx.flushAndClear();

        queryCounter.reset();
        List<Long> visible = queryFactory.select(post.id)
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(PortalExposure.portalVisible(CRITERIA))
                .fetch();
        assertThat(queryCounter.count()).isEqualTo(1);

        Set<Long> expected = Set.of(posts.get("normal").getId(), posts.get("exactly200").getId(),
                posts.get("joinedExactlyAtDelay").getId());
        assertThat(visible).containsExactlyInAnyOrderElementsOf(expected);

        Set<Long> excludedIds = Set.of(excluded.getId());
        for (Map.Entry<String, Post> entry : posts.entrySet()) {
            Post found = em.find(Post.class, entry.getValue().getId());
            List<PortalIneligibility> reasons = PortalExposure.evaluate(found, CRITERIA,
                    excludedIds.contains(found.getId()));
            assertThat(reasons.isEmpty()).as(entry.getKey()).isEqualTo(visible.contains(found.getId()));
        }
        assertThat(reasons(posts, "private", excludedIds)).containsExactly(PortalIneligibility.NOT_BODY_VISIBLE);
        assertThat(reasons(posts, "portalOff", excludedIds)).containsExactly(PortalIneligibility.BLOG_PORTAL_DISABLED);
        assertThat(reasons(posts, "excluded", excludedIds)).containsExactly(PortalIneligibility.EXCLUDED);
        assertThat(reasons(posts, "newMember", excludedIds)).containsExactly(PortalIneligibility.NEW_MEMBER);
        assertThat(reasons(posts, "short199", excludedIds)).containsExactly(PortalIneligibility.TOO_SHORT);
        assertThat(reasons(posts, "draft", excludedIds)).contains(PortalIneligibility.NOT_BODY_VISIBLE);
    }

    @Test
    void textLengthCountsCodePoints() {
        assertThat(PortalExposure.textLength(null)).isEqualTo(-1);
        assertThat(PortalExposure.textLength("가나")).isEqualTo(2);
        assertThat(PortalExposure.textLength("😀")).isEqualTo(1);
    }

    private List<PortalIneligibility> reasons(Map<String, Post> posts, String key, Set<Long> excludedIds) {
        Post found = em.find(Post.class, posts.get(key).getId());
        return PortalExposure.evaluate(found, CRITERIA, excludedIds.contains(found.getId()));
    }
}
