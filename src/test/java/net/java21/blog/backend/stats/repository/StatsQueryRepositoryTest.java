package net.java21.blog.backend.stats.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.stats.dto.VisitStatsResponse;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 통계 조회수 상위 글(T057): 휴지통 제외 모든 상태·공개 범위, 조회수 많은 순, 쿼리 1회. */
@JpaRepositoryTest
@Import(StatsQueryRepository.class)
class StatsQueryRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private StatsQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    @Test
    void topPostsByViewCountExcludingTrash() {
        JpaFixtures fx = new JpaFixtures(em);
        Blog blog = fx.blog(fx.user("marco"), "marco");
        Post popular = views(fx.published(blog, "인기", null, 1), 50);
        Post privatePost = views(fx.post(blog, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 2), 20);
        views(fx.post(blog, "휴지통", null, PostStatus.DELETED, PostVisibility.PUBLIC, 3), 90);
        Post draft = fx.post(blog, "임시", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 4);
        views(fx.published(fx.blog(fx.user("polo"), "polo"), "남의 글", null, 5), 70);
        fx.flushAndClear();

        queryCounter.reset();
        assertThat(repository.findTopPosts(blog.getId(), 10)).containsExactly(
                new VisitStatsResponse.TopPost(popular.getId(), "인기", 50),
                new VisitStatsResponse.TopPost(privatePost.getId(), "비공개", 20),
                new VisitStatsResponse.TopPost(draft.getId(), "임시", 0));
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.findTopPosts(blog.getId(), 1)).hasSize(1);
    }

    private static Post views(Post post, int count) {
        return TestEntities.with(post, "viewCount", count);
    }
}
