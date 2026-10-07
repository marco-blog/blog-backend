package net.java21.blog.backend.external.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.external.dto.ClassificationReviewResponse;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

/** 007 T061: 검수 목록 — ACTIVE 글의 그 상태만, 오래된 것 먼저, 블로그 필터, 글·블로그·검토자 함께 쿼리 2회. */
@JpaRepositoryTest
@Import(ClassificationReviewQueryRepository.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ClassificationReviewQueryRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private ClassificationReviewQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    @Test
    void listsPendingOfActivePostsOldestFirstWithBlogFilterInTwoQueries() {
        JpaFixtures f = new JpaFixtures(em);
        ExternalFixtures x = new ExternalFixtures(em);
        User admin = f.user("admin");
        Topic major = f.topic(null, "knowledge", 1);
        Topic it = f.topic(major, "it-internet", 1);
        Topic science = f.topic(major, "science", 2);
        ExternalBlog a = x.blog(null, it, ExternalBlogStatus.ACTIVE);
        ExternalBlog b = x.blog(null, science, ExternalBlogStatus.ACTIVE);
        ClassificationReview newer = x.review(x.post(a, "A2", it, null), science, 0.5);
        ClassificationReview older = x.review(x.post(b, "B1", science, null), it, 0.3);
        ClassificationReview third = x.review(x.post(a, "A3", it, null), null, 0.1);
        ExternalPost removed = x.removed(a, "Gone", it, RemovedReason.ADMIN);
        x.review(removed, science, 0.2);
        ClassificationReview confirmed = x.review(x.post(b, "B2", science, null), it, 0.6);
        confirmed.confirm(it, admin, JpaFixtures.T0);
        em.flush();
        em.createQuery("update ClassificationReview r set r.createdAt = :t where r.id = :id")
                .setParameter("t", JpaFixtures.T0.minusSeconds(60)).setParameter("id", older.getId()).executeUpdate();
        em.clear();

        queryCounter.reset();
        Page<ClassificationReview> page = repository.findPage(ReviewStatus.PENDING, null, PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(ClassificationReview::getId)
                .containsExactly(older.getId(), newer.getId(), third.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
        ClassificationReviewResponse first = ClassificationReviewResponse.of(page.getContent().getFirst());
        assertThat(first.post().title()).isEqualTo("B1");
        assertThat(first.externalBlog().defaultTopicId()).isEqualTo(science.getId());
        assertThat(first.predictedTopicId()).isEqualTo(it.getId());
        assertThat(ClassificationReviewResponse.of(page.getContent().get(2)).predictedTopicId()).isNull();
        assertThat(queryCounter.count()).isEqualTo(2);

        assertThat(repository.findPage(ReviewStatus.PENDING, a.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(ClassificationReview::getId).containsExactly(newer.getId(), third.getId());
        Page<ClassificationReview> closed = repository.findPage(ReviewStatus.CONFIRMED, null, PageRequest.of(0, 20));
        assertThat(closed.getContent()).extracting(ClassificationReview::getId).containsExactly(confirmed.getId());
        assertThat(ClassificationReviewResponse.of(closed.getContent().getFirst()).reviewedBy().nickname())
                .isEqualTo("admin");
        assertThat(repository.findPage(ReviewStatus.PENDING, null, PageRequest.of(1, 2)).getContent())
                .extracting(ClassificationReview::getId).containsExactly(third.getId());
    }
}
