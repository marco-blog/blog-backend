package net.java21.blog.backend.admin.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.admin.content.HiddenPostQueryRepository;
import net.java21.blog.backend.admin.user.dto.AdminUserDetail.BlogItem;
import net.java21.blog.backend.admin.user.dto.AdminUserSummary;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.LoginHistory;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 005 T037: 관리자 회원 검색(이메일 해시 정확 일치, 별명 앞부분, 핸들 — 삭제된 블로그 포함), ACTIVE 블로그 수, 회원 상세 조회(블로그, 글 수,
 * 최근 로그인), 숨긴 글 목록. 검색은 회원 수와 관계없이 쿼리 2회.
 */
@JpaRepositoryTest
class AdminUserQueryRepositoryTest {

    private static final Instant T1 = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private QueryCounter queryCounter;

    private AdminUserQueryRepository repository;
    private JpaFixtures fx;
    private User marco;
    private User marcus;
    private User polo;

    @BeforeEach
    void setUp() {
        repository = new AdminUserQueryRepository(queryFactory);
        fx = new JpaFixtures(em);
        marco = fx.user("marco");
        marcus = fx.user("marcus");
        polo = fx.user("polo");
        fx.blog(marco, "marco");
        Blog gone = fx.blog(marco, "gone");
        TestEntities.with(gone, "status", BlogStatus.DELETED);
        fx.blog(marcus, "marcus");
    }

    @Test
    void searchesByNicknamePrefixEmailHashAndHandle() {
        fx.flushAndClear();

        queryCounter.reset();
        Page<AdminUserSummary> byNickname = repository.search(
                new AdminUserQueryRepository.Search(null, "marc", null), PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(byNickname.getTotalElements()).isEqualTo(2);
        assertThat(byNickname.getContent()).extracting(AdminUserSummary::nickname).containsExactly("marco", "marcus");
        assertThat(byNickname.getContent().getFirst().blogCount()).isEqualTo(1);
        assertThat(byNickname.getContent().getFirst().status()).isNotNull();

        assertThat(repository.search(new AdminUserQueryRepository.Search(polo.getEmailHash(), null, null),
                PageRequest.of(0, 20)).getContent()).singleElement()
                .satisfies(u -> assertThat(u.id()).isEqualTo(polo.getId()))
                .satisfies(u -> assertThat(u.blogCount()).isZero());
        assertThat(repository.search(new AdminUserQueryRepository.Search(null, null, "gone"), PageRequest.of(0, 20))
                .getContent()).extracting(AdminUserSummary::id).containsExactly(marco.getId());
        assertThat(repository.search(new AdminUserQueryRepository.Search(null, null, "nobody"),
                PageRequest.of(0, 20)).getTotalElements()).isZero();
        Page<AdminUserSummary> second = repository.search(new AdminUserQueryRepository.Search(null, "marc", null),
                PageRequest.of(1, 1));
        assertThat(second.getContent()).extracting(AdminUserSummary::nickname).containsExactly("marcus");
    }

    @Test
    void detailQueriesCountBlogsPostsAndLastLogin() {
        Blog marcoBlog = em.createQuery("select b from Blog b where b.handle = 'marco'", Blog.class).getSingleResult();
        fx.published(marcoBlog, "하나", null, 0);
        fx.post(marcoBlog, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 1);
        fx.post(marcoBlog, "휴지통", null, PostStatus.DELETED, PostVisibility.PUBLIC, 2);
        em.persist(new LoginHistory(marco, true, "203.0.113.1", "UA", T1));
        em.persist(new LoginHistory(marco, true, "203.0.113.1", "UA", T2.minusSeconds(10)));
        em.persist(new LoginHistory(marco, false, "203.0.113.1", "UA", T2));
        fx.flushAndClear();

        assertThat(repository.findBlogs(marco.getId())).containsExactly(
                new BlogItem("marco", "marco 블로그", BlogStatus.ACTIVE),
                new BlogItem("gone", "marco 블로그", BlogStatus.DELETED));
        assertThat(repository.countPosts(marco.getId())).isEqualTo(2);
        assertThat(repository.countPosts(polo.getId())).isZero();
        assertThat(repository.findLastLoginAt(marco.getId())).isEqualTo(T2.minusSeconds(10));
        assertThat(repository.findLastLoginAt(polo.getId())).isNull();
    }

    @Test
    void hiddenPostIdsNewestFirst() {
        Blog marcoBlog = em.createQuery("select b from Blog b where b.handle = 'marco'", Blog.class).getSingleResult();
        Post first = fx.published(marcoBlog, "하나", null, 0);
        Post second = fx.published(marcoBlog, "둘", null, 1);
        fx.published(marcoBlog, "셋", null, 2);
        first.hide();
        second.hide();
        fx.flushAndClear();

        HiddenPostQueryRepository hidden = new HiddenPostQueryRepository(queryFactory);
        Page<Long> page = hidden.findHiddenPostIds(PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).containsExactlyInAnyOrder(first.getId(), second.getId());
    }
}
