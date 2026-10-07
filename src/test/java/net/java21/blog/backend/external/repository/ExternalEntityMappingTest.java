package net.java21.blog.backend.external.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PersistenceException;

import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalBlogVerification;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostDailyClick;
import net.java21.blog.backend.external.domain.ExternalPostDailyClickId;
import net.java21.blog.backend.external.domain.TopicMappingRule;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** 007 T011: 엔티티 6개 저장·조회, 유일 제약, 포털 제외의 내부/외부 글 하나만, 연관관계 LAZY. */
@JpaRepositoryTest
class ExternalEntityMappingTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private ExternalPostDailyClickRepository clickRepository;

    private JpaFixtures f;
    private ExternalFixtures x;
    private User member;
    private Topic topic;

    @BeforeEach
    void setUp() {
        f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        member = f.user("member");
        topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
    }

    @Test
    void savesAndReadsAllEntities() {
        ExternalBlog blog = x.blog(member, "https://a.example/feed", topic, ExternalBlogStatus.ACTIVE);
        ExternalPost post = x.post(blog, "title", topic, null);
        post.recordClassifier(topic, 0.66666, "keyword-v1");
        ExternalBlogVerification verification = new ExternalBlogVerification(member, blog.getFeedUrlHash(),
                "java21-verify-AAAAAAAAAAAA", Instant.parse("2026-10-02T00:00:00Z"));
        em.persist(verification);
        TopicMappingRule rule = new TopicMappingRule("spring", topic, 5, member);
        em.persist(rule);
        ClassificationReview review = x.review(post, topic, 0.5);
        em.persist(new ExternalPostDailyClick(post, LocalDate.of(2026, 10, 1), 3));
        f.flushAndClear();

        ExternalPost loaded = postRepository.findById(post.getId()).orElseThrow();
        assertThat(loaded.getFeedTerms()).containsExactly(post.getFeedTerms().getFirst());
        assertThat(loaded.getClassifierConfidence()).isEqualByComparingTo(new BigDecimal("0.667"));
        assertThat(loaded.getTopicSource()).isEqualTo(TopicSource.DEFAULT);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(blogRepository.findById(blog.getId()).orElseThrow().getStatus()).isEqualTo(ExternalBlogStatus.ACTIVE);
        assertThat(em.find(ExternalBlogVerification.class, verification.getId()).getCode())
                .isEqualTo("java21-verify-AAAAAAAAAAAA");
        assertThat(em.find(TopicMappingRule.class, rule.getId()).getPriority()).isEqualTo(5);
        assertThat(em.find(ClassificationReview.class, review.getId()).getConfidence())
                .isEqualByComparingTo(new BigDecimal("0.500"));
        assertThat(clickRepository.findById(new ExternalPostDailyClickId(post.getId(), LocalDate.of(2026, 10, 1)))
                .orElseThrow().getClicks()).isEqualTo(3);
    }

    @Test
    void samePostTwiceInOneBlogIsRejected() {
        ExternalBlog blog = x.blog(member, topic, ExternalBlogStatus.ACTIVE);
        FeedItem item = new FeedItem("g", "https://p.example/1", "t", null, null, null, List.of());
        em.persist(new ExternalPost(blog, item, "a".repeat(64), "b".repeat(64), topic, TopicSource.DEFAULT, Instant.now()));
        em.flush();
        assertThatThrownBy(() -> {
            em.persist(new ExternalPost(blog, item, "a".repeat(64), "c".repeat(64), topic, TopicSource.DEFAULT,
                    Instant.now()));
            em.flush();
        }).isInstanceOfAny(PersistenceException.class,
                DataIntegrityViolationException.class);
    }

    @Test
    void sameLinkTwiceInOneBlogIsRejected() {
        ExternalBlog blog = x.blog(member, topic, ExternalBlogStatus.ACTIVE);
        FeedItem item = new FeedItem(null, "https://p.example/1", "t", null, null, null, List.of());
        em.persist(new ExternalPost(blog, item, null, "b".repeat(64), topic, TopicSource.DEFAULT, Instant.now()));
        assertThatThrownBy(() -> {
            em.persist(new ExternalPost(blog, item, null, "b".repeat(64), topic, TopicSource.DEFAULT, Instant.now()));
            em.flush();
        }).isInstanceOfAny(PersistenceException.class,
                DataIntegrityViolationException.class);
    }

    @Test
    void ruleKeywordIsUnique() {
        em.persist(new TopicMappingRule("spring", topic, 0, member));
        assertThatThrownBy(() -> {
            em.persist(new TopicMappingRule("spring", topic, 1, member));
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void oneReviewPerPost() {
        ExternalPost post = x.post(x.blog(member, topic, ExternalBlogStatus.ACTIVE), "t", topic, null);
        x.review(post, null, 0);
        assertThatThrownBy(() -> {
            x.review(post, null, 0);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void verificationCodeIsUnique() {
        String hash = FeedUrlNormalizer.hash("https://a.example/feed");
        em.persist(new ExternalBlogVerification(member, hash, "java21-verify-SAME00000000", Instant.now()));
        assertThatThrownBy(() -> {
            em.persist(new ExternalBlogVerification(member, hash, "java21-verify-SAME00000000", Instant.now()));
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void portalExclusionTargetsExactlyOnePost() {
        ExternalPost external = x.post(x.blog(member, topic, ExternalBlogStatus.ACTIVE), "t", topic, null);
        Post internal = f.publishedText(f.blog(member, "handle1"), "p", "text", topic, JpaFixtures.T0);
        em.persist(new PortalExclusion(external, "reason", member));
        em.persist(new PortalExclusion(internal, "reason", member));
        f.flushAndClear();
        assertThat(em.createQuery("select count(e) from PortalExclusion e", Long.class).getSingleResult()).isEqualTo(2);

        assertThatThrownBy(() -> em.createNativeQuery("INSERT INTO portal_exclusions (excluded_by, reason, created_at,"
                + " updated_at) VALUES (" + member.getId() + ", 'r', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                .executeUpdate()).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> em.createNativeQuery("INSERT INTO portal_exclusions (excluded_by, reason, post_id,"
                + " external_post_id, created_at, updated_at) VALUES (" + member.getId() + ", 'r', " + internal.getId()
                + ", " + external.getId() + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)").executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void associationsAreLazy() {
        for (Class<?> type : List.of(ExternalBlog.class, ExternalPost.class, ExternalBlogVerification.class,
                ExternalPostDailyClick.class, TopicMappingRule.class, ClassificationReview.class,
                PortalExclusion.class)) {
            for (Field field : type.getDeclaredFields()) {
                ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
                OneToOne oneToOne = field.getAnnotation(OneToOne.class);
                if (manyToOne != null) {
                    assertThat(manyToOne.fetch()).as(type.getSimpleName() + "." + field.getName())
                            .isEqualTo(FetchType.LAZY);
                }
                if (oneToOne != null) {
                    assertThat(oneToOne.fetch()).as(type.getSimpleName() + "." + field.getName())
                            .isEqualTo(FetchType.LAZY);
                }
            }
        }
    }
}
