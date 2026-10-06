package net.java21.blog.backend.like;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.like.dto.LikeStateResponse;
import net.java21.blog.backend.like.service.PostLikeService;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요 동시성(T015, quickstart #2, research D1): 같은 회원·같은 글로 20번 동시에 누르면 행 1·수 1, 이어서 20번 동시에 취소하면 행 0·수 0.
 * 서로 다른 회원 20명이 같은 글을 동시에 눌러도 교착 상태 없이 수 20. 스레드마다 따로 커밋해야 하므로 테스트 트랜잭션을 쓰지 않고 끝에 지운다.
 */
@MySqlRepositoryTest
@Import(PostLikeService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostLikeConcurrencyTest {

    private static final int REQUESTS = 20;

    @Autowired
    private PostLikeService service;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BlogRepository blogRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();
    private String tag;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        User owner = user("owner");
        blog = blogRepository.save(new Blog(owner, "lk" + tag, "블로그"));
        Post p = new Post(blog, "글");
        p.publish("글", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, Instant.now());
        post = postRepository.save(p);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM post_likes WHERE post_id = ?", post.getId());
        jdbc.update("DELETE FROM posts WHERE id = ?", post.getId());
        jdbc.update("DELETE FROM blogs WHERE id = ?", blog.getId());
        userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    void sameMemberConcurrentLikesAndUnlikesKeepOneRowAndExactCount() throws Exception {
        long reader = user("reader").getId();

        List<LikeStateResponse> liked = concurrently(i -> () -> service.like(reader, post.getId()));
        assertThat(liked).allSatisfy(r -> {
            assertThat(r.liked()).isTrue();
            assertThat(r.likeCount()).isEqualTo(1);
        });
        assertThat(rows()).isEqualTo(1);
        assertThat(likeCount()).isEqualTo(1);

        List<LikeStateResponse> unliked = concurrently(i -> () -> service.unlike(reader, post.getId()));
        assertThat(unliked).allSatisfy(r -> {
            assertThat(r.liked()).isFalse();
            assertThat(r.likeCount()).isZero();
        });
        assertThat(rows()).isZero();
        assertThat(likeCount()).isZero();
    }

    @Test
    void differentMembersConcurrentLikesCountEveryone() throws Exception {
        List<Long> readers = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            readers.add(user("r" + i).getId());
        }

        concurrently(i -> () -> service.like(readers.get(i), post.getId()));

        assertThat(rows()).isEqualTo(REQUESTS);
        assertThat(likeCount()).isEqualTo(REQUESTS);
    }

    private List<LikeStateResponse> concurrently(IntFunction<Callable<LikeStateResponse>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LikeStateResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS; i++) {
                Callable<LikeStateResponse> call = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<LikeStateResponse> results = new ArrayList<>();
            for (Future<LikeStateResponse> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private User user(String name) {
        String key = name + tag;
        User user = userRepository.save(new User(key + "@example.com", (key + "0".repeat(64)).substring(0, 64),
                "$2a$hash", name, null, null, "2026-10-06", Instant.now()));
        userIds.add(user.getId());
        return user;
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM post_likes WHERE post_id = ?", Integer.class, post.getId());
    }

    private int likeCount() {
        return jdbc.queryForObject("SELECT like_count FROM posts WHERE id = ?", Integer.class, post.getId());
    }
}
