package net.java21.blog.backend.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 스키마(MySQL)에서만 확인되는 글 동작:
 * <ul>
 *   <li>조회수 증가 UPDATE가 {@code ON UPDATE CURRENT_TIMESTAMP(6)}에도 {@code updated_at}을 바꾸지 않는다.</li>
 *   <li>영구 삭제 때 {@code post_drafts}·{@code post_tags}가 FK {@code ON DELETE CASCADE}로 함께 지워지고, {@code tags_json}이 JSON 컬럼에 저장된다.</li>
 *   <li>삭제된 블로그 비우기가 실제 {@code categories} 테이블(자기 참조 FK, posts FK)에서 동작한다.</li>
 * </ul>
 */
@MySqlRepositoryTest
@Import(TrashPurgeRepository.class)
class PostMySqlBehaviourTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostDraftRepository postDraftRepository;
    @Autowired
    private TrashPurgeRepository trashPurgeRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void viewCountIncrementKeepsUpdatedAt() {
        Post post = persistPost(persistBlog());
        em.flush();
        Instant updatedAt = jdbc.queryForObject("SELECT updated_at FROM posts WHERE id = ?", java.sql.Timestamp.class,
                post.getId()).toInstant();

        postRepository.incrementViewCount(post.getId());

        assertThat(jdbc.queryForObject("SELECT view_count FROM posts WHERE id = ?", Integer.class, post.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM posts WHERE id = ?", java.sql.Timestamp.class,
                post.getId()).toInstant()).isEqualTo(updatedAt);
    }

    @Test
    void purgeCascadesDraftAndTagsAndEmptiesDeletedBlog() {
        Blog blog = persistBlog();
        Post post = persistPost(blog);
        PostDraft draft = new PostDraft(post);
        draft.write("사본", "본문", null, List.of("spring", "jpa"), NOW);
        postDraftRepository.save(draft);
        em.flush();
        em.clear();
        assertThat(postDraftRepository.findById(post.getId()).orElseThrow().getTags()).containsExactly("spring", "jpa");
        jdbc.update("INSERT INTO tags (name) VALUES (?)", "t-" + post.getId());
        Long tagId = jdbc.queryForObject("SELECT id FROM tags WHERE name = ?", Long.class, "t-" + post.getId());
        jdbc.update("INSERT INTO post_tags (post_id, tag_id) VALUES (?, ?)", post.getId(), tagId);
        jdbc.update("INSERT INTO categories (blog_id, name) VALUES (?, '상위')", blog.getId());
        Long parent = jdbc.queryForObject("SELECT id FROM categories WHERE blog_id = ? AND parent_id IS NULL",
                Long.class, blog.getId());
        jdbc.update("INSERT INTO categories (blog_id, parent_id, name) VALUES (?, ?, '하위')", blog.getId(), parent);
        jdbc.update("UPDATE posts SET category_id = ?, status = 'DELETED', deleted_at = ? WHERE id = ?", parent,
                java.sql.Timestamp.from(NOW), post.getId());
        jdbc.update("UPDATE blogs SET status = 'DELETED', deleted_at = ? WHERE id = ?", java.sql.Timestamp.from(NOW),
                blog.getId());

        assertThat(trashPurgeRepository.deletePosts(List.of(post.getId()))).isEqualTo(1);
        assertThat(trashPurgeRepository.purgeBlogs(List.of(blog.getId()))).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_tags WHERE post_id = ?", Integer.class, post.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_drafts WHERE post_id = ?", Integer.class,
                post.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE blog_id = ?", Integer.class,
                blog.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT title FROM blogs WHERE id = ?", String.class, blog.getId())).isEmpty();
    }

    private Blog persistBlog() {
        String tag = UUID.randomUUID().toString().replace("-", "");
        User user = new User("my-" + tag + "@example.com", (tag + tag).substring(0, 64), "$2a$hash", "my", null, null,
                "2026-10-06", NOW);
        em.persist(user);
        Blog blog = new Blog(user, "m" + tag.substring(0, 12), "블로그");
        em.persist(blog);
        return blog;
    }

    private Post persistPost(Blog blog) {
        Post post = new Post(blog, "글");
        post.publish("글", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW);
        em.persist(post);
        return post;
    }
}
