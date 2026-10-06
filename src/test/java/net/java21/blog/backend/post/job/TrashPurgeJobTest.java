package net.java21.blog.backend.post.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.post.repository.TrashPurgeRepository;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 휴지통 비우기(T066, FR-084, FR-159, R26, quickstart #16): 30일 지난 휴지통 글을 정해진 건수씩 영구 삭제하고(작성 중 사본 포함),
 * 30일 지난 삭제된 블로그는 카테고리를 지우고 행은 남기되 제목·소개를 비우며, 처리 건수를 로그에 남긴다.
 */
@JpaRepositoryTest
@ExtendWith(OutputCaptureExtension.class)
@Import(TrashPurgeJobTest.Config.class)
class TrashPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T03:30:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TrashPurgeRepository.class)
    static class Config {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        /** 배치 크기 2: 여러 번에 나눠 처리하는지 확인한다. */
        @Bean
        TrashPurgeJob trashPurgeJob(TrashPurgeRepository repository, TransactionTemplate transactionTemplate,
                MutableClock clock) {
            return new TrashPurgeJob(repository, transactionTemplate, new JobsProperties("0 30 3 * * *", RETENTION, 2),
                    clock);
        }
    }

    @Autowired
    private TrashPurgeJob job;
    @Autowired
    private EntityManager em;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostDraftRepository postDraftRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        // 카테고리 엔티티는 US2에서 생긴다. 그 전까지 H2에는 엔티티로 만든 테이블만 있으므로 같은 모양으로 만든다.
        jdbc.execute("CREATE TABLE IF NOT EXISTS categories (id BIGINT AUTO_INCREMENT PRIMARY KEY, blog_id BIGINT NOT NULL,"
                + " parent_id BIGINT, name VARCHAR(50) NOT NULL, sort_order INT DEFAULT 0 NOT NULL,"
                + " created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,"
                + " updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,"
                + " CONSTRAINT fk_test_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id))");
        // 트랙백(005)·포털(003) 엔티티는 아직 없다. 영구 삭제가 이 테이블들의 posts FK(ON DELETE CASCADE 없음)를 먼저 정리하는지
        // 확인하려고 실제 스키마와 같은 FK만 가진 모양으로 만든다.
        jdbc.execute("CREATE TABLE IF NOT EXISTS trackbacks (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " post_id BIGINT NOT NULL, source_post_id BIGINT, source_url VARCHAR(1000) NOT NULL,"
                + " CONSTRAINT fk_test_trackbacks_post FOREIGN KEY (post_id) REFERENCES posts (id),"
                + " CONSTRAINT fk_test_trackbacks_source FOREIGN KEY (source_post_id) REFERENCES posts (id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS portal_curations (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " post_id BIGINT NOT NULL,"
                + " CONSTRAINT fk_test_portal_curations_post FOREIGN KEY (post_id) REFERENCES posts (id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS portal_exclusions (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " post_id BIGINT,"
                + " CONSTRAINT fk_test_portal_exclusions_post FOREIGN KEY (post_id) REFERENCES posts (id))");
        owner = new User("marco@example.com", "a".repeat(64), "$2a$hash", "marco", null, null, "2026-10-06", NOW);
        em.persist(owner);
        blog = new Blog(owner, "marco", "마르코의 블로그");
        em.persist(blog);
    }

    @Test
    void purgesTrashOlderThanRetentionInBatches(CapturedOutput output) {
        Post old1 = trashed(NOW.minus(RETENTION).minusSeconds(1));
        Post old2 = trashed(NOW.minus(Duration.ofDays(40)));
        Post old3 = trashed(NOW.minus(Duration.ofDays(31)));
        PostDraft draft = new PostDraft(old1);
        draft.write("사본", "본문", null, List.of("a"), NOW);
        em.persist(draft);
        Post recent = trashed(NOW.minus(Duration.ofDays(29)));
        Post live = published();
        em.flush();
        em.clear();

        TrashPurgeJob.Result result = job.purge();

        assertThat(result.posts()).isEqualTo(3);
        assertThat(postRepository.findAllById(List.of(old1.getId(), old2.getId(), old3.getId()))).isEmpty();
        assertThat(postDraftRepository.findById(old1.getId())).isEmpty();
        assertThat(postRepository.findById(recent.getId())).isPresent();
        assertThat(postRepository.findById(live.getId())).isPresent();
        assertThat(output).contains("Trash purge finished: posts=3, blogs=0");
    }

    @Test
    void deletedBlogKeepsRowButLosesCategoriesTitleAndDescription() {
        Blog second = new Blog(owner, "marco-old", "옛 블로그");
        second.changeDescription("소개");
        em.persist(second);
        Post post = new Post(second, "글");
        post.publish("글", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW);
        em.persist(post);
        em.flush();
        long parent = insertCategory(second.getId(), null, "상위");
        insertCategory(second.getId(), parent, "하위");
        long keep = insertCategory(blog.getId(), null, "남는 카테고리");
        jdbc.update("UPDATE posts SET category_id = ? WHERE id = ?", parent, post.getId());
        Instant deletedAt = NOW.minus(Duration.ofDays(31));
        second.delete(deletedAt);
        post.moveToTrash(deletedAt);
        Blog recentlyDeleted = new Blog(owner, "marco-new", "최근 삭제");
        em.persist(recentlyDeleted);
        recentlyDeleted.delete(NOW.minus(Duration.ofDays(1)));
        em.flush();
        em.clear();

        TrashPurgeJob.Result result = job.purge();

        assertThat(result).isEqualTo(new TrashPurgeJob.Result(1, 1));
        Blog purged = em.find(Blog.class, second.getId());
        assertThat(purged.getStatus()).isEqualTo(BlogStatus.DELETED);
        assertThat(purged.getHandle()).isEqualTo("marco-old");
        assertThat(purged.getTitle()).isEmpty();
        assertThat(purged.getDescription()).isNull();
        assertThat(jdbc.queryForList("SELECT id FROM categories", Long.class)).containsExactly(keep);
        assertThat(em.find(Blog.class, recentlyDeleted.getId()).getTitle()).isEqualTo("최근 삭제");

        assertThat(job.purge()).isEqualTo(new TrashPurgeJob.Result(0, 0));
    }

    /** T189: 영구 삭제되는 글의 댓글(답글 먼저)과 트랙백·포털 행을 먼저 지운다(이 FK들은 ON DELETE CASCADE가 없다). */
    @Test
    void purgeRemovesCommentsRepliesFirstAndOtherRowsReferencingThePost() {
        Post old = trashed(NOW.minus(Duration.ofDays(31)));
        Post live = published();
        User reader = new User("reader@example.com", "b".repeat(64), "$2a$hash", "reader", null, null, "2026-10-06",
                NOW);
        em.persist(reader);
        Comment top = new Comment(old, reader, null, "댓글");
        em.persist(top);
        em.persist(new Comment(old, owner, top, "답글"));
        Comment placeholder = new Comment(old, reader, null, "지운 댓글");
        placeholder.markDeleted();
        em.persist(placeholder);
        em.persist(new Comment(old, owner, placeholder, "남은 답글"));
        Comment kept = new Comment(live, reader, null, "남는 댓글");
        em.persist(kept);
        em.flush();
        jdbc.update("INSERT INTO trackbacks (post_id, source_url) VALUES (?, 'https://x.test/1')", old.getId());
        jdbc.update("INSERT INTO trackbacks (post_id, source_post_id, source_url) VALUES (?, ?, 'https://x.test/2')",
                live.getId(), old.getId());
        jdbc.update("INSERT INTO portal_curations (post_id) VALUES (?)", old.getId());
        jdbc.update("INSERT INTO portal_exclusions (post_id) VALUES (?)", old.getId());
        em.clear();

        TrashPurgeJob.Result result = job.purge();

        assertThat(result.posts()).isEqualTo(1);
        assertThat(postRepository.findById(old.getId())).isEmpty();
        assertThat(jdbc.queryForList("SELECT id FROM comments", Long.class)).containsExactly(kept.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trackbacks WHERE post_id = ?", Integer.class,
                old.getId())).isZero();
        assertThat(jdbc.queryForList("SELECT source_post_id FROM trackbacks WHERE post_id = ?", Long.class,
                live.getId())).containsExactly((Long) null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_curations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_exclusions", Integer.class)).isZero();
    }

    @Test
    void scheduledRunPurges() {
        Post old = trashed(NOW.minus(Duration.ofDays(31)));
        em.flush();
        em.clear();

        job.run();

        assertThat(postRepository.findById(old.getId())).isEmpty();
    }

    private long insertCategory(Long blogId, Long parentId, String name) {
        jdbc.update("INSERT INTO categories (blog_id, parent_id, name) VALUES (?, ?, ?)", blogId, parentId, name);
        return jdbc.queryForObject("SELECT MAX(id) FROM categories", Long.class);
    }

    private Post trashed(Instant deletedAt) {
        Post post = published();
        post.moveToTrash(deletedAt);
        return post;
    }

    private Post published() {
        Post post = new Post(blog, "글");
        post.publish("글", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW.minusSeconds(1));
        em.persist(post);
        return post;
    }
}
