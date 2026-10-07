package net.java21.blog.backend.post.repository;

import static org.assertj.core.api.Assertions.assertThat;


import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 보관함 발행 시각 조회(T051): 목록 노출 가능 글만, 최신순, 쿼리 1회. */
@JpaRepositoryTest
@Import(ArchiveQueryRepository.class)
class ArchiveQueryRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private ArchiveQueryRepository repository;

    @Test
    void publishedTimesOfListablePostsOnly() {
        JpaFixtures fx = new JpaFixtures(em);
        Blog blog = fx.blog(fx.user("marco"), "marco");
        fx.published(blog, "첫 글", null, 1);
        fx.published(blog, "둘째 글", null, 60 * 24 * 40);
        fx.post(blog, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 2);
        fx.post(blog, "임시", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 3);
        fx.post(blog, "휴지통", null, PostStatus.DELETED, PostVisibility.PUBLIC, 4);
        Blog empty = fx.blog(fx.user("polo"), "polo");
        fx.flushAndClear();

        queryCounter.reset();
        assertThat(repository.findPublishedTimes(blog.getId())).containsExactly(
                JpaFixtures.T0.plusSeconds(60L * 60 * 24 * 40), JpaFixtures.T0.plusSeconds(60));
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.findPublishedTimes(empty.getId())).isEmpty();
    }
}
