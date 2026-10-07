package net.java21.blog.backend.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.search.service.SearchQuery;
import net.java21.blog.backend.search.service.SearchQueryParser;
import net.java21.blog.backend.search.SearchProperties;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.domain.PostTag;
import net.java21.blog.backend.tag.domain.Tag;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 서비스 전체 검색(T060, FR-035, AS1·2, SC-004, research D4). FULLTEXT ngram은 H2에 없으므로 테스트 MySQL에서만 돈다.
 * 제목·본문·태그 각각의 일치, 두 낱말 AND, 노출 매트릭스 001 행 전부(PRIVATE·DRAFT·DELETED·작성자 SUSPENDED·WITHDRAWN·삭제된 블로그)와 005 HIDDEN 제외,
 * 발행 최신순, 페이지·전체 수, 블로그 handle·title, 행마다 본문 노출 여부, 쿼리 수 고정(목록 1 + 수 1).
 * InnoDB FULLTEXT는 커밋된 행만 찾으므로 테스트 트랜잭션 없이 저장·커밋하고 끝에 지운다. 다른 테스트의 데이터와 섞이지 않게
 * 실행마다 새로 만든 낱말로 검색한다(영어 불용어 a·i가 들어간 2-gram은 색인되지 않으므로 그 두 글자는 쓰지 않는다).
 */
@MySqlRepositoryTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostSearchRepository.class, QueryCounter.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class PostSearchRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final String LETTERS = "bcdfghjklmnopqrstuvwxyz";

    @Autowired
    private EntityManager em;
    @Autowired
    private PostSearchRepository repository;
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
    private final List<Long> tagIds = new ArrayList<>();
    private String word;
    private String other;

    @BeforeEach
    void setUp() {
        word = randomWord();
        other = randomWord();
    }

    @AfterEach
    void cleanUp() {
        postIds.forEach(id -> jdbc.update("DELETE FROM post_tags WHERE post_id = ?", id));
        tagIds.forEach(id -> jdbc.update("DELETE FROM tags WHERE id = ?", id));
        postIds.forEach(id -> jdbc.update("DELETE FROM posts WHERE id = ?", id));
        blogIds.forEach(id -> jdbc.update("DELETE FROM blogs WHERE id = ?", id));
        userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    void findsTitleBodyAndTagMatchesNewestFirst() {
        long[] ids = new long[3];
        inTransaction(() -> {
            Blog blog = blog(user(UserStatus.ACTIVE), "검색 블로그");
            ids[0] = post(blog, "제목에 " + word, "본문", PostVisibility.PUBLIC, 1).getId();
            ids[1] = post(blog, "본문에 있는 글", "여기 " + word + " 있음", PostVisibility.PUBLIC, 2).getId();
            Post tagged = post(blog, "태그만 있는 글", "본문", PostVisibility.PUBLIC, 3);
            tag(tagged, word);
            ids[2] = tagged.getId();
            post(blog, "관계없는 글", "본문", PostVisibility.PUBLIC, 4);
        });

        Page<SearchPostRow> page = repository.search(parser.parse(word), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(SearchPostRow::id).containsExactly(ids[2], ids[1], ids[0]);
        assertThat(page.getTotalElements()).isEqualTo(3);
        SearchPostRow first = page.getContent().get(2);
        assertThat(first.title()).isEqualTo("제목에 " + word);
        assertThat(first.summary()).isEqualTo("요약");
        assertThat(first.blogHandle()).startsWith("s");
        assertThat(first.blogTitle()).isEqualTo("검색 블로그");
        assertThat(first.bodyVisible()).isTrue();
    }

    @Test
    void allTermsMustAppearInTheSameField() {
        long[] both = new long[1];
        inTransaction(() -> {
            Blog blog = blog(user(UserStatus.ACTIVE), "블로그");
            both[0] = post(blog, word + " 그리고 " + other, "본문", PostVisibility.PUBLIC, 1).getId();
            post(blog, word + " 하나만", "본문", PostVisibility.PUBLIC, 2);
            post(blog, "다른 하나 " + other, "본문", PostVisibility.PUBLIC, 3);
            // 낱말이 제목과 태그에 흩어진 글은 나오지 않는다(받아들인 한계, 결정 9)
            tag(post(blog, "제목 " + word, "본문", PostVisibility.PUBLIC, 4), other);
        });

        Page<SearchPostRow> page = repository.search(parser.parse(word + " " + other), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(SearchPostRow::id).containsExactly(both[0]);
    }

    @Test
    void hidesEveryRowOfTheExposureMatrix() {
        long[] visible = new long[1];
        inTransaction(() -> {
            Blog blog = blog(user(UserStatus.ACTIVE), "블로그");
            visible[0] = post(blog, "공개 " + word, "본문", PostVisibility.PUBLIC, 1).getId();
            post(blog, "비공개 " + word, "본문", PostVisibility.PRIVATE, 2);
            Post draft = new Post(blog, "임시저장 " + word);
            em.persist(draft);
            postIds.add(draft.getId());
            Post trashed = post(blog, "휴지통 " + word, "본문", PostVisibility.PUBLIC, 3);
            trashed.moveToTrash(T0.plusSeconds(600));
            // 005 관리자 숨김(HIDDEN)
            post(blog, "숨긴 글 " + word, "본문", PostVisibility.PUBLIC, 7).hide();
            post(blog(user(UserStatus.SUSPENDED), "정지"), "정지 회원 " + word, "본문", PostVisibility.PUBLIC, 4);
            post(blog(user(UserStatus.WITHDRAWN), "탈퇴"), "탈퇴 회원 " + word, "본문", PostVisibility.PUBLIC, 5);
            Blog deleted = blog(user(UserStatus.ACTIVE), "삭제된 블로그");
            post(deleted, "삭제된 블로그 " + word, "본문", PostVisibility.PUBLIC, 6);
            deleted.delete(T0.plusSeconds(700));
        });

        Page<SearchPostRow> page = repository.search(parser.parse(word), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(SearchPostRow::id).containsExactly(visible[0]);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void pagesWithTotalCountInTwoQueries() {
        long[] ids = new long[5];
        inTransaction(() -> {
            Blog blog = blog(user(UserStatus.ACTIVE), "블로그");
            for (int i = 0; i < 5; i++) {
                Post p = post(blog, "글 " + i + " " + word, "본문", PostVisibility.PUBLIC, i);
                if (i % 2 == 0) {
                    tag(p, word + i);
                }
                ids[i] = p.getId();
            }
        });
        SearchQuery query = parser.parse(word);

        queryCounter.reset();
        Page<SearchPostRow> second = repository.search(query, PageRequest.of(1, 2));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(5);
        assertThat(second.getContent()).extracting(SearchPostRow::id).containsExactly(ids[2], ids[1]);
    }

    @Test
    void blogFilterKeepsOnlyThatBlogsPostsAndTheExposureRules() {
        long[] ids = new long[2];
        inTransaction(() -> {
            Blog mine = blog(user(UserStatus.ACTIVE), "내 블로그");
            ids[0] = mine.getId();
            ids[1] = post(mine, "내 글 " + word, "본문", PostVisibility.PUBLIC, 1).getId();
            post(mine, "내 비공개 " + word, "본문", PostVisibility.PRIVATE, 2);
            Post tagged = post(blog(user(UserStatus.ACTIVE), "남의 블로그"), "남의 글", "본문", PostVisibility.PUBLIC, 3);
            tag(tagged, word);
        });

        queryCounter.reset();
        Page<SearchPostRow> page = repository.search(parser.parse(word), ids[0], PageRequest.of(0, 20));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getContent()).extracting(SearchPostRow::id).containsExactly(ids[1]);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(repository.search(parser.parse(word), PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            work.run();
            em.flush();
        });
    }

    private User user(UserStatus status) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        User user = new User("sr-" + unique + "@example.com", (unique + unique).substring(0, 64), "$2a$hash", "검색",
                null, null, "2026-10-06", T0);
        TestEntities.with(user, "status", status);
        em.persist(user);
        userIds.add(user.getId());
        return user;
    }

    private Blog blog(User owner, String title) {
        Blog blog = new Blog(owner, "s" + UUID.randomUUID().toString().substring(0, 8), title);
        em.persist(blog);
        blogIds.add(blog.getId());
        return blog;
    }

    private Post post(Blog blog, String title, String text, PostVisibility visibility, int minutes) {
        Post p = new Post(blog, title);
        p.publish(title, text, "<p>" + text + "</p>", text, "요약", null, visibility, true,
                T0.plusSeconds(60L * minutes));
        em.persist(p);
        em.flush();
        postIds.add(p.getId());
        return p;
    }

    private void tag(Post post, String name) {
        Tag t = new Tag(name);
        em.persist(t);
        em.persist(new PostTag(post, t));
        em.flush();
        tagIds.add(t.getId());
    }

    private static String randomWord() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(LETTERS.charAt(ThreadLocalRandom.current().nextInt(LETTERS.length())));
        }
        return sb.toString();
    }
}
