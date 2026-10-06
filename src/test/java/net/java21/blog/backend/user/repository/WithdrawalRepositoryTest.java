package net.java21.blog.backend.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 탈퇴 때 그 회원의 모든 블로그(삭제된 블로그 포함)의 모든 글을 비공개로(T121, FR-009). UPDATE 1회. */
@JpaRepositoryTest
@Import(WithdrawalRepository.class)
class WithdrawalRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private WithdrawalRepository repository;
    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;

    @Test
    void makesEveryPostOfEveryBlogPrivate() {
        User marco = user("marco@example.com", "a");
        User other = user("other@example.com", "b");
        Blog first = blog(marco, "marco");
        Blog second = blog(marco, "marco-dev");
        Blog deleted = blog(marco, "marco-old");
        deleted.delete(NOW);
        Blog others = blog(other, "other");
        Post p1 = published(first, PostVisibility.PUBLIC);
        Post p2 = published(second, PostVisibility.PUBLIC);
        Post p3 = published(deleted, PostVisibility.PUBLIC);
        Post draft = post(first);
        Post trashed = published(first, PostVisibility.PUBLIC);
        trashed.moveToTrash(NOW);
        Post othersPost = published(others, PostVisibility.PUBLIC);
        em.flush();
        em.clear();

        queryCounter.reset();
        long updated = repository.makeAllPostsPrivate(marco.getId());
        assertThat(queryCounter.count()).isEqualTo(1);

        assertThat(updated).isEqualTo(5);
        for (Post post : new Post[] {p1, p2, p3, draft, trashed}) {
            assertThat(em.find(Post.class, post.getId()).getVisibility()).isEqualTo(PostVisibility.PRIVATE);
        }
        assertThat(em.find(Post.class, trashed.getId()).getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(em.find(Post.class, othersPost.getId()).getVisibility()).isEqualTo(PostVisibility.PUBLIC);
    }

    private User user(String email, String hashChar) {
        User user = new User(email, hashChar.repeat(64), "$2a$hash", email, null, null, "2026-10-06", NOW);
        em.persist(user);
        return user;
    }

    private Blog blog(User owner, String handle) {
        Blog blog = new Blog(owner, handle, handle);
        em.persist(blog);
        return blog;
    }

    private Post post(Blog blog) {
        Post post = new Post(blog, "제목");
        em.persist(post);
        return post;
    }

    private Post published(Blog blog, PostVisibility visibility) {
        Post post = post(blog);
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, visibility, true, NOW);
        return post;
    }
}
