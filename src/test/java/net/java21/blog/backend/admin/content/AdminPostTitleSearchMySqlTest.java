package net.java21.blog.backend.admin.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.content.AdminContentSearchRepository.PostCriteria;
import net.java21.blog.backend.admin.content.dto.AdminPostRow;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.search.SearchProperties;
import net.java21.blog.backend.search.service.SearchQueryParser;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 006 T023(FR-102, research A4): 관리자 글 제목 검색은 MySQL FULLTEXT ngram({@code ft_posts_title})이고, 공개 범위·상태와
 * 상관없이(비공개·초안·휴지통 포함) 찾으며 최신 생성순, 쿼리 2회다. InnoDB FULLTEXT는 커밋된 행만 찾으므로 트랜잭션 없이 저장하고
 * 끝에 지운다.
 */
@MySqlRepositoryTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AdminContentSearchRepository.class, QueryCounter.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class AdminPostTitleSearchMySqlTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final String LETTERS = "bcdfghjklmnopqrstuvwxyz";

    @Autowired
    private EntityManager em;
    @Autowired
    private AdminContentSearchRepository repository;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final SearchQueryParser parser = new SearchQueryParser(new SearchProperties(5, 2));
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> blogIds = new ArrayList<>();
    private final List<Long> postIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        postIds.forEach(id -> jdbc.update("DELETE FROM posts WHERE id = ?", id));
        blogIds.forEach(id -> jdbc.update("DELETE FROM blogs WHERE id = ?", id));
        userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    void titleSearchFindsEveryStatusNewestFirst() {
        String word = randomWord();
        String other = randomWord();
        long[] ids = new long[4];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Blog blog = blog();
            ids[0] = post(blog, word + " 공개", PostStatus.PUBLISHED, PostVisibility.PUBLIC).getId();
            ids[1] = post(blog, word + " 비공개", PostStatus.PUBLISHED, PostVisibility.PRIVATE).getId();
            ids[2] = post(blog, word + " 초안", PostStatus.DRAFT, PostVisibility.PUBLIC).getId();
            ids[3] = post(blog, other + " 다른 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC).getId();
            em.flush();
        });

        queryCounter.reset();
        Page<AdminPostRow> page = repository.posts(
                new PostCriteria(parser.parse(word).booleanQuery(), null, null, null, null), PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AdminPostRow::id).containsExactly(ids[2], ids[1], ids[0]);
        assertThat(page.getTotalElements()).isEqualTo(3);

        Page<AdminPostRow> drafts = repository.posts(
                new PostCriteria(parser.parse(word).booleanQuery(), null, null, PostStatus.DRAFT, null),
                PageRequest.of(0, 20));
        assertThat(drafts.getContent()).extracting(AdminPostRow::id).containsExactly(ids[2]);
        Page<AdminPostRow> both = repository.posts(
                new PostCriteria(parser.parse(word + " " + other).booleanQuery(), null, null, null, null),
                PageRequest.of(0, 20));
        assertThat(both.getContent()).as("낱말 AND").isEmpty();
    }

    private Blog blog() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        User user = new User("at-" + unique + "@example.com", (unique + unique).substring(0, 64), "$2a$hash", "관리검색",
                null, null, "2026-10-06", T0);
        em.persist(user);
        userIds.add(user.getId());
        Blog blog = new Blog(user, "a" + unique.substring(0, 8), "관리 검색 블로그");
        em.persist(blog);
        blogIds.add(blog.getId());
        return blog;
    }

    private Post post(Blog blog, String title, PostStatus status, PostVisibility visibility) {
        Post p = new Post(blog, title);
        if (status != PostStatus.DRAFT) {
            p.publish(title, "본문", "<p>본문</p>", "본문", "요약", null, visibility, true, T0);
        }
        em.persist(p);
        em.flush();
        postIds.add(p.getId());
        return p;
    }

    private static String randomWord() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(LETTERS.charAt(ThreadLocalRandom.current().nextInt(LETTERS.length())));
        }
        return sb.toString();
    }
}
