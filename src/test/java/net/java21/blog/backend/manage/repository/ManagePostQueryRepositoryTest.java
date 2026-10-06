package net.java21.blog.backend.manage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.manage.dto.ManagePostFilter;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 블로그 관리 글 목록·대시보드·일괄 작업 쿼리(T148, 006 FR-100·101): {@code status}(생략 시 휴지통 제외, DELETED는 보관 기간 안의
 * 휴지통 글)·{@code visibility}·{@code category}·제목 {@code q} 동적 조건, 최신순 페이지, 글 수와 무관한 고정 쿼리 수,
 * 일괄 작업은 블로그 조건이 붙은 UPDATE 한 번.
 */
@JpaRepositoryTest
@Import(ManagePostQueryRepository.class)
class ManagePostQueryRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    /** 지금 - 30일 */
    private static final Instant TRASH_CUTOFF = Instant.parse("2026-09-06T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private ManagePostQueryRepository repository;

    private Blog marco;
    private Blog other;
    private int hashSeq;

    @BeforeEach
    void setUp() {
        marco = persistBlog(persistUser("marco"), "marco");
        other = persistBlog(persistUser("other"), "other");
    }

    @Test
    void defaultListExcludesTrashNewestFirstWithDraftFlagAndFixedQueries() {
        Post draft = persistPost(marco, "임시 글", PostStatus.DRAFT, PostVisibility.PUBLIC);
        Post published = persistPost(marco, "발행 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        Post privatePost = persistPost(marco, "비공개 글", PostStatus.PUBLISHED, PostVisibility.PRIVATE);
        Post trashed = persistPost(marco, "버린 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        trashed.moveToTrash(NOW.minusSeconds(60));
        persistPost(other, "남의 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        persistDraft(published, "고치는 중");
        persistDraft(draft, "임시 글");
        flushAndClear();

        queryCounter.reset();
        Page<ManagePostRow> page = repository.findPosts(marco.getId(), ManagePostFilter.ALL, TRASH_CUTOFF,
                PageRequest.of(0, 20));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(ManagePostRow::id)
                .containsExactly(privatePost.getId(), published.getId(), draft.getId());
        assertThat(page.getContent()).extracting(ManagePostRow::hasDraft).containsExactly(false, true, true);
        assertThat(page.getContent()).extracting(ManagePostRow::deletedAt).containsOnlyNulls();
    }

    @Test
    void manyPostsStillTwoQueriesAndPaged() {
        for (int i = 0; i < 25; i++) {
            Post p = persistPost(marco, "글 " + i, PostStatus.PUBLISHED, PostVisibility.PUBLIC);
            if (i % 2 == 0) {
                persistDraft(p, "사본 " + i);
            }
        }
        flushAndClear();

        queryCounter.reset();
        Page<ManagePostRow> second = repository.findPosts(marco.getId(), ManagePostFilter.ALL, TRASH_CUTOFF,
                PageRequest.of(1, 20));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(25);
        assertThat(second.getContent()).hasSize(5);
        assertThat(second.getContent().getLast().title()).isEqualTo("글 0");
    }

    @Test
    void trashListsOnlyDeletedPostsWithinRetentionNewestDeletionFirst() {
        Post older = persistPost(marco, "먼저 버린 글", PostStatus.PUBLISHED, PostVisibility.PRIVATE);
        older.moveToTrash(NOW.minusSeconds(3600));
        Post newer = persistPost(marco, "나중에 버린 글", PostStatus.DRAFT, PostVisibility.PUBLIC);
        newer.moveToTrash(NOW.minusSeconds(60));
        Post expired = persistPost(marco, "31일 지난 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        expired.moveToTrash(TRASH_CUTOFF.minusSeconds(86_400));
        persistPost(marco, "살아 있는 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        flushAndClear();

        Page<ManagePostRow> trash = repository.findPosts(marco.getId(),
                new ManagePostFilter(PostStatus.DELETED, null, null, null), TRASH_CUTOFF, PageRequest.of(0, 20));

        assertThat(trash.getContent()).extracting(ManagePostRow::id).containsExactly(newer.getId(), older.getId());
        assertThat(trash.getContent()).extracting(ManagePostRow::deletedAt)
                .containsExactly(NOW.minusSeconds(60), NOW.minusSeconds(3600));
        assertThat(trash.getContent()).extracting(ManagePostRow::status).containsOnly(PostStatus.DELETED);
    }

    @Test
    void filtersByStatusVisibilityCategoryAndTitleQuery() {
        Post draft = persistPost(marco, "Spring 정리", PostStatus.DRAFT, PostVisibility.PUBLIC);
        Post springPublic = persistPost(marco, "spring boot 4", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        Post jpaPrivate = persistPost(marco, "JPA 100% 정복", PostStatus.PUBLISHED, PostVisibility.PRIVATE);
        Category jpa = new Category(marco, null, "JPA", 0);
        em.persist(jpa);
        jpaPrivate.classify(jpa);
        persistPost(other, "spring 남의 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        flushAndClear();

        assertThat(ids(new ManagePostFilter(PostStatus.DRAFT, null, null, null))).containsExactly(draft.getId());
        assertThat(ids(new ManagePostFilter(PostStatus.PUBLISHED, null, null, null)))
                .containsExactly(jpaPrivate.getId(), springPublic.getId());
        assertThat(ids(new ManagePostFilter(null, PostVisibility.PRIVATE, null, null)))
                .containsExactly(jpaPrivate.getId());
        assertThat(ids(new ManagePostFilter(null, null, jpa.getId(), null))).containsExactly(jpaPrivate.getId());
        assertThat(ids(new ManagePostFilter(null, null, null, "SPRING")))
                .containsExactly(springPublic.getId(), draft.getId());
        assertThat(ids(new ManagePostFilter(PostStatus.PUBLISHED, PostVisibility.PUBLIC, null, "spring")))
                .containsExactly(springPublic.getId());
        assertThat(ids(new ManagePostFilter(null, null, null, "100%"))).containsExactly(jpaPrivate.getId());
        assertThat(ids(new ManagePostFilter(null, null, null, "_"))).isEmpty();
        assertThat(ids(new ManagePostFilter(null, null, null, "   "))).hasSize(3);
    }

    @Test
    void dashboardCountsDraftsAndReadsFiveRecentPostsInOneQueryEach() {
        persistPost(marco, "임시 1", PostStatus.DRAFT, PostVisibility.PUBLIC);
        persistPost(marco, "임시 2", PostStatus.DRAFT, PostVisibility.PUBLIC);
        Post trashedDraft = persistPost(marco, "버린 임시", PostStatus.DRAFT, PostVisibility.PUBLIC);
        trashedDraft.moveToTrash(NOW);
        persistPost(other, "남의 임시", PostStatus.DRAFT, PostVisibility.PUBLIC);
        for (int i = 0; i < 6; i++) {
            persistPost(marco, "발행 " + i, PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        }
        flushAndClear();

        queryCounter.reset();
        assertThat(repository.countDrafts(marco.getId())).isEqualTo(2);
        assertThat(queryCounter.count()).isEqualTo(1);

        queryCounter.reset();
        List<ManagePostRow> recent = repository.findRecentPosts(marco.getId(), 5);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(recent).extracting(ManagePostRow::title)
                .containsExactly("발행 5", "발행 4", "발행 3", "발행 2", "발행 1");
    }

    @Test
    void countOwnedCountsOnlyThisBlogsPosts() {
        Post mine = persistPost(marco, "내 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        Post trashed = persistPost(marco, "버린 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        trashed.moveToTrash(NOW);
        Post theirs = persistPost(other, "남의 글", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        flushAndClear();

        queryCounter.reset();
        assertThat(repository.countOwned(marco.getId(), List.of(mine.getId(), trashed.getId()))).isEqualTo(2);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.countOwned(marco.getId(), List.of(mine.getId(), theirs.getId(), -1L))).isEqualTo(1);
    }

    @Test
    void changeVisibilityIsOneSetBasedUpdateLimitedToTheBlog() {
        Post a = persistPost(marco, "A", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        Post b = persistPost(marco, "B", PostStatus.DRAFT, PostVisibility.PUBLIC);
        Post theirs = persistPost(other, "C", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        flushAndClear();

        queryCounter.reset();
        long updated = repository.changeVisibility(marco.getId(), List.of(a.getId(), b.getId(), theirs.getId()),
                PostVisibility.PRIVATE, NOW);

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(updated).isEqualTo(2);
        em.clear();
        assertThat(em.find(Post.class, a.getId()).getVisibility()).isEqualTo(PostVisibility.PRIVATE);
        assertThat(em.find(Post.class, a.getId()).getUpdatedAt()).isEqualTo(NOW);
        assertThat(em.find(Post.class, b.getId()).getVisibility()).isEqualTo(PostVisibility.PRIVATE);
        assertThat(em.find(Post.class, theirs.getId()).getVisibility()).isEqualTo(PostVisibility.PUBLIC);
    }

    @Test
    void moveToTrashKeepsPreviousStatusAndSkipsTrashAndOtherBlogs() {
        Post published = persistPost(marco, "A", PostStatus.PUBLISHED, PostVisibility.PRIVATE);
        Post draft = persistPost(marco, "B", PostStatus.DRAFT, PostVisibility.PUBLIC);
        Post already = persistPost(marco, "C", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        already.moveToTrash(T0);
        Post theirs = persistPost(other, "D", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        flushAndClear();

        queryCounter.reset();
        long updated = repository.moveToTrash(marco.getId(),
                List.of(published.getId(), draft.getId(), already.getId(), theirs.getId()), NOW);

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(updated).isEqualTo(2);
        em.clear();
        Post p = em.find(Post.class, published.getId());
        assertThat(p.getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(p.getStatusBeforeDelete()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(p.getDeletedAt()).isEqualTo(NOW);
        assertThat(p.getVisibility()).isEqualTo(PostVisibility.PRIVATE);
        Post d = em.find(Post.class, draft.getId());
        assertThat(d.getStatusBeforeDelete()).isEqualTo(PostStatus.DRAFT);
        assertThat(em.find(Post.class, already.getId()).getDeletedAt()).isEqualTo(T0);
        assertThat(em.find(Post.class, theirs.getId()).getStatus()).isEqualTo(PostStatus.PUBLISHED);

        d.restore();
        assertThat(d.getStatus()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    void moveCategoryIsSetBasedLimitedToTheBlogAndAlignsDraftCopies() {
        Category spring = new Category(marco, null, "Spring", 0);
        em.persist(spring);
        Category boot = new Category(marco, spring, "Boot", 0);
        em.persist(boot);
        Post a = persistPost(marco, "A", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        a.classify(spring);
        Post b = persistPost(marco, "B", PostStatus.DRAFT, PostVisibility.PUBLIC);
        persistDraft(b, "B");
        Post theirs = persistPost(other, "C", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        persistDraft(theirs, "C");
        flushAndClear();

        queryCounter.reset();
        long moved = repository.moveCategory(marco.getId(), List.of(a.getId(), b.getId(), theirs.getId()),
                em.getReference(Category.class, boot.getId()), NOW);

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(moved).isEqualTo(2);
        em.clear();
        assertThat(em.find(Post.class, a.getId()).getCategory().getId()).isEqualTo(boot.getId());
        assertThat(em.find(Post.class, b.getId()).getCategory().getId()).isEqualTo(boot.getId());
        assertThat(em.find(PostDraft.class, b.getId()).getCategoryId()).isEqualTo(boot.getId());
        assertThat(em.find(Post.class, theirs.getId()).getCategory()).isNull();
        assertThat(em.find(PostDraft.class, theirs.getId()).getCategoryId()).isNull();
        assertThat(ids(new ManagePostFilter(null, null, spring.getId(), null)))
                .containsExactly(b.getId(), a.getId());

        assertThat(repository.moveCategory(marco.getId(), List.of(a.getId()), null, NOW)).isEqualTo(1);
        em.clear();
        assertThat(em.find(Post.class, a.getId()).getCategory()).isNull();
        Page<ManagePostRow> rows = repository.findPosts(marco.getId(), ManagePostFilter.ALL, TRASH_CUTOFF,
                PageRequest.of(0, 20));
        assertThat(rows.getContent()).extracting(ManagePostRow::categoryName).containsExactly("Boot", null);
    }

    private List<Long> ids(ManagePostFilter filter) {
        return repository.findPosts(marco.getId(), filter, TRASH_CUTOFF, PageRequest.of(0, 20)).getContent()
                .stream().map(ManagePostRow::id).toList();
    }

    private User persistUser(String nickname) {
        String hash = String.valueOf((char) ('a' + hashSeq++)).repeat(64);
        User u = new User(nickname + "@example.com", hash, "$2a$hash", nickname, null, null, "2026-10-06", T0);
        em.persist(u);
        return u;
    }

    private Blog persistBlog(User owner, String handle) {
        Blog b = new Blog(owner, handle, Blog.defaultTitle(owner.getNickname()));
        em.persist(b);
        return b;
    }

    private Post persistPost(Blog b, String title, PostStatus status, PostVisibility visibility) {
        Post p = new Post(b, title);
        if (status == PostStatus.PUBLISHED) {
            p.publish(title, "본문", "<p>본문</p>", "본문", "본문", null, visibility, true, T0);
        }
        em.persist(p);
        return p;
    }

    private void persistDraft(Post p, String title) {
        PostDraft d = new PostDraft(p);
        d.write(title, "본문", null, List.of(), T0);
        em.persist(d);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
