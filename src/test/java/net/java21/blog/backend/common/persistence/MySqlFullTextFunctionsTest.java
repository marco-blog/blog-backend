package net.java21.blog.backend.common.persistence;

import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.tag.domain.QPostTag.postTag;
import static net.java21.blog.backend.tag.domain.QTag.tag;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.tag.domain.PostTag;
import net.java21.blog.backend.tag.domain.Tag;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MATCH 함수 등록(T006, research D4): QueryDSL 템플릿 {@code match_title_content}·{@code match_title}·{@code match_tag}가
 * {@code MATCH(...) AGAINST (? IN BOOLEAN MODE)}로 실행되어 스냅숏 스키마의 FULLTEXT ngram 인덱스로 글·태그를 찾는다.
 * InnoDB FULLTEXT는 커밋된 행만 찾으므로 테스트 트랜잭션 없이 저장·커밋하고 끝에 지운다.
 */
@MySqlRepositoryTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MySqlFullTextFunctionsTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> postIds = new ArrayList<>();
    private Long userId;
    private Long blogId;
    private Long tagId;
    private Long springBoot;
    private Long javaBasics;
    private Long taggedOnly;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = new User("ft-" + unique + "@example.com", (unique + "f".repeat(64)).substring(0, 64),
                    "$2a$hash", "ft", null, null, "2026-10-06", Instant.now());
            em.persist(user);
            Blog blog = new Blog(user, "ft" + unique, "검색 블로그");
            em.persist(blog);
            springBoot = persist(blog, "스프링 부트 입문 " + unique, "자바로 만드는 웹 서버");
            javaBasics = persist(blog, "자바 기초 " + unique, "람다와 스트림, 스프링은 나오지 않음".replace("스프링", "봄"));
            taggedOnly = persist(blog, "태그만 있는 글 " + unique, "본문");
            Tag t = new Tag("코틀린" + unique);
            em.persist(t);
            em.persist(new PostTag(em.find(Post.class, taggedOnly), t));
            userId = user.getId();
            blogId = blog.getId();
            tagId = t.getId();
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM post_tags WHERE tag_id = ?", tagId);
        jdbc.update("DELETE FROM tags WHERE id = ?", tagId);
        postIds.forEach(id -> jdbc.update("DELETE FROM posts WHERE id = ?", id));
        jdbc.update("DELETE FROM blogs WHERE id = ?", blogId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void matchTitleContentFindsKoreanTwoCharacterTermsInTitleOrBody() {
        assertThat(titleContent("+\"스프링\"")).containsExactly(springBoot);
        assertThat(titleContent("+\"서버\"")).containsExactly(springBoot);
        assertThat(titleContent("+\"스프링\" +\"부트\"")).containsExactly(springBoot);
        assertThat(titleContent("+\"스프링\" +\"람다\"")).isEmpty();
        assertThat(titleContent("+\"자바\"")).containsExactlyInAnyOrder(springBoot, javaBasics);
    }

    @Test
    void matchTitleSearchesOnlyTheTitle() {
        assertThat(queryFactory.select(post.id).from(post)
                .where(post.id.in(postIds), MySqlFullTextFunctions.matchTitle(post.title, "+\"자바\"").gt(0.0))
                .fetch()).containsExactly(javaBasics);
    }

    @Test
    void matchTagFindsPostsByTagName() {
        List<Long> found = queryFactory.select(postTag.post.id).from(postTag).join(postTag.tag, tag)
                .where(postTag.post.id.in(postIds), MySqlFullTextFunctions.matchTag(tag.name, "+\"코틀린\"").gt(0.0))
                .fetch();
        assertThat(found).containsExactly(taggedOnly);
    }

    private List<Long> titleContent(String query) {
        return queryFactory.select(post.id).from(post)
                .where(post.id.in(postIds),
                        MySqlFullTextFunctions.matchTitleContent(post.title, post.contentText, query).gt(0.0))
                .orderBy(post.id.asc())
                .fetch();
    }

    private Long persist(Blog blog, String title, String text) {
        Post p = new Post(blog, title);
        p.publish(title, text, "<p>" + text + "</p>", text, text, null, PostVisibility.PUBLIC, true, Instant.now());
        em.persist(p);
        em.flush();
        postIds.add(p.getId());
        return p.getId();
    }
}
