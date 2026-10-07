package net.java21.blog.backend.post.repository;

import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.StatsProperties;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.trackback.TrackbackUrls;
import net.java21.blog.backend.trackback.repository.TrackbackQueryRepository;
import net.java21.blog.backend.trackback.service.TrackbackSendService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.LatestDraftResponse;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PostLink;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.post.service.PostAccess;
import net.java21.blog.backend.post.service.PostService;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.post.dto.PostListFilter;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 노출 조각과 글 조회(T063, FR-011, FR-018, SC-004). 노출 매트릭스의 001 행마다 "목록 노출 가능"·"본문 노출 가능"이 맞게 거르고,
 * 블로그 글 목록·상세가 글 수와 관계없이 고정된 쿼리 수로 읽힌다.
 */
@JpaRepositoryTest
@Import(PostExposureRepositoryTest.Services.class)
class PostExposureRepositoryTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({StatsProperties.class, SiteProperties.class, TrackbackProperties.class})
    @Import({PostQueryRepository.class, PostService.class, PostAccess.class, BlogAccess.class, CategoryAccess.class,
            TagQueryRepository.class, BlogCalendar.class, TrackbackQueryRepository.class, TrackbackUrls.class,
            TrackbackSendService.class})
    static class Services {
    }

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private PostQueryRepository postQueryRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostDraftRepository postDraftRepository;
    @Autowired
    private PostService postService;

    private User marco;
    private Blog marcoBlog;
    private int hashSeq;

    @BeforeEach
    void setUp() {
        marco = persistUser("marco", UserStatus.ACTIVE);
        marcoBlog = persistBlog(marco, "marco");
    }

    @Test
    void exposureFragmentsFollowTheMatrix() {
        Post publicPost = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0);
        persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PRIVATE, T0.plusSeconds(1));
        persistPost(marcoBlog, PostStatus.DRAFT, PostVisibility.PUBLIC, null);
        Post trashed = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0.plusSeconds(2));
        trashed.moveToTrash(T0.plusSeconds(3));
        Blog suspendedBlog = persistBlog(persistUser("bad", UserStatus.SUSPENDED), "bad");
        persistPost(suspendedBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0);
        Blog withdrawnBlog = persistBlog(persistUser("gone", UserStatus.WITHDRAWN), "gone");
        persistPost(withdrawnBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0);
        Blog deletedBlog = persistBlog(marco, "old");
        persistPost(deletedBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0);
        deletedBlog.delete(T0);
        flushAndClear();

        assertThat(idsMatching(PostExposure.listable())).containsExactly(publicPost.getId());
        assertThat(idsMatching(PostExposure.bodyVisible())).containsExactly(publicPost.getId());

        List<Post> all = queryFactory.selectFrom(post).join(post.blog, blog).fetchJoin()
                .join(blog.user, user).fetchJoin().fetch();
        assertThat(all).filteredOn(PostExposure::isListable).extracting(Post::getId).containsExactly(publicPost.getId());
        assertThat(all).filteredOn(PostExposure::isBodyVisible).extracting(Post::getId)
                .containsExactly(publicPost.getId());
        // 004: 보호 글(PROTECTED)은 목록에만 나오고 본문은 못 본다.
        assertThat(PostExposure.hasListableWithoutBody()).isTrue();
    }

    /** 004 노출 매트릭스 PROTECTED·SCHEDULED 행(T073): 쿼리 조각과 자바 판단이 같은 결과를 낸다. */
    @Test
    void protectedAndScheduledRowsFollowTheMatrix() {
        Post publicPost = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0);
        Post protectedPost = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PROTECTED, T0.plusSeconds(1));
        Post scheduledPublic = persistScheduled(marcoBlog, PostVisibility.PUBLIC, T0.plusSeconds(3600));
        Post scheduledProtected = persistScheduled(marcoBlog, PostVisibility.PROTECTED, T0.plusSeconds(3600));
        User reader = persistUser("reader", UserStatus.ACTIVE);
        flushAndClear();

        assertThat(idsMatching(PostExposure.listable())).containsExactly(publicPost.getId(), protectedPost.getId());
        assertThat(idsMatching(PostExposure.bodyVisible())).containsExactly(publicPost.getId());

        List<Post> all = queryFactory.selectFrom(post).join(post.blog, blog).fetchJoin()
                .join(blog.user, user).fetchJoin().fetch();
        assertThat(all).filteredOn(PostExposure::isListable).extracting(Post::getId)
                .containsExactlyElementsOf(idsMatching(PostExposure.listable()));
        assertThat(all).filteredOn(PostExposure::isBodyVisible).extracting(Post::getId)
                .containsExactlyElementsOf(idsMatching(PostExposure.bodyVisible()));
        Post loadedProtected = byId(all, protectedPost.getId());
        Post loadedScheduled = byId(all, scheduledPublic.getId());
        // 보호 글은 주인 외에도 상세(잠금 화면)가 열리고, 열람 쿠키가 없으면 잠긴다.
        assertThat(PostExposure.isDetailVisibleTo(loadedProtected, reader.getId())).isTrue();
        assertThat(PostExposure.isDetailVisibleTo(loadedProtected, null)).isTrue();
        assertThat(PostExposure.isLocked(loadedProtected, reader.getId(), false)).isTrue();
        assertThat(PostExposure.isLocked(loadedProtected, reader.getId(), true)).isFalse();
        assertThat(PostExposure.isLocked(loadedProtected, marco.getId(), false)).isFalse();
        assertThat(PostExposure.isLocked(byId(all, publicPost.getId()), null, false)).isFalse();
        // 예약 글은 공개 범위와 관계없이 주인에게만.
        assertThat(PostExposure.isDetailVisibleTo(loadedScheduled, reader.getId())).isFalse();
        assertThat(PostExposure.isDetailVisibleTo(loadedScheduled, null)).isFalse();
        assertThat(PostExposure.isDetailVisibleTo(loadedScheduled, marco.getId())).isTrue();
        assertThat(PostExposure.isDetailVisibleTo(byId(all, scheduledProtected.getId()), reader.getId())).isFalse();
    }

    /** 004 T074: 주인 외 목록에서 보호 글은 제목만(요약·대표 이미지 null). */
    @Test
    void protectedPostIsTitleOnlyInReaderList() {
        Post protectedPost = new Post(marcoBlog, "보호");
        protectedPost.publish("보호", "본문", "<p>본문</p>", "본문", "요약", "/media/k3Jd9fQ2xLmA7pZ0bR5tYw",
                PostVisibility.PROTECTED, true, T0);
        protectedPost.applyProtection("$2a$hash");
        em.persist(protectedPost);
        persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0.plusSeconds(1));
        flushAndClear();

        Page<PostSummaryResponse> page = postService.blogPosts("marco", PostListFilter.NONE, PageRequest.of(0, 20));

        PostSummaryResponse locked = page.getContent().stream().filter(p -> p.id().equals(protectedPost.getId()))
                .findFirst().orElseThrow();
        assertThat(locked.title()).isEqualTo("보호");
        assertThat(locked.summary()).isNull();
        assertThat(locked.thumbnailUrl()).isNull();
        assertThat(locked.scheduledAt()).isNull();
        assertThat(page.getContent()).filteredOn(p -> p.visibility() == PostVisibility.PUBLIC)
                .extracting(PostSummaryResponse::summary).containsOnly("본문");
    }

    /** 004 T077: 잠긴 보호 글의 상세는 제목·작성자·발행일만, 태그 쿼리를 하지 않는다. 주인은 본문과 예약 시각. */
    @Test
    void lockedDetailHidesBodyAndSkipsTags() {
        Post protectedPost = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PROTECTED, T0);
        flushAndClear();

        queryCounter.reset();
        PostDetailResponse locked = postService.detail(protectedPost.getId(), null);
        // 글, 이전, 다음(태그 없음)
        assertThat(queryCounter.count()).isEqualTo(3);
        assertThat(locked.locked()).isTrue();
        assertThat(locked.title()).isEqualTo("글");
        assertThat(locked.contentHtml()).isNull();
        assertThat(locked.summary()).isNull();
        assertThat(locked.thumbnailUrl()).isNull();
        assertThat(locked.category()).isNull();
        assertThat(locked.topicId()).isNull();
        assertThat(locked.tags()).isEmpty();
        assertThat(locked.publishedAt()).isEqualTo(T0);

        em.clear();
        PostDetailResponse unlocked = postService.detail(protectedPost.getId(), null, p -> true);
        assertThat(unlocked.locked()).isFalse();
        assertThat(unlocked.contentHtml()).isEqualTo("<p>본문</p>");

        em.clear();
        PostDetailResponse owner = postService.detail(protectedPost.getId(), marco.getId());
        assertThat(owner.locked()).isFalse();
        assertThat(owner.contentMarkdown()).isEqualTo("본문");
    }

    private static Post byId(List<Post> posts, Long id) {
        return posts.stream().filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
    }

    private Post persistScheduled(Blog b, PostVisibility visibility, Instant scheduledAt) {
        Post p = new Post(b, "예약");
        p.schedule("예약", "본문", "<p>본문</p>", "본문", "본문", null, visibility, true, scheduledAt);
        p.applyProtection(visibility == PostVisibility.PROTECTED ? "$2a$hash" : null);
        em.persist(p);
        return p;
    }

    @Test
    void blogPostListIsNewestFirstPagedWithTotalAndFixedQueryCount() {
        for (int i = 0; i < 25; i++) {
            persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0.plusSeconds(i));
        }
        persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PRIVATE, T0.plusSeconds(100));
        persistPost(marcoBlog, PostStatus.DRAFT, PostVisibility.PUBLIC, null);
        Blog other = persistBlog(persistUser("other", UserStatus.ACTIVE), "other");
        persistPost(other, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0.plusSeconds(200));
        flushAndClear();

        queryCounter.reset();
        Page<PostSummaryRow> first = postQueryRepository.findListablePosts(marcoBlog.getId(), PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(first.getTotalElements()).isEqualTo(25);
        assertThat(first.getContent()).hasSize(20);
        assertThat(first.getContent().getFirst().publishedAt()).isEqualTo(T0.plusSeconds(24));
        assertThat(first.getContent()).extracting(PostSummaryRow::publishedAt).isSortedAccordingTo((a, b) -> b.compareTo(a));

        queryCounter.reset();
        Page<PostSummaryRow> second = postQueryRepository.findListablePosts(marcoBlog.getId(), PageRequest.of(1, 20));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(second.getContent()).hasSize(5);
        assertThat(second.getContent().getLast().publishedAt()).isEqualTo(T0);
    }

    @Test
    void serviceListAndDetailUseFixedQueryCounts() {
        Post target = null;
        for (int i = 0; i < 10; i++) {
            Post p = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0.plusSeconds(i));
            if (i == 5) {
                target = p;
            }
        }
        flushAndClear();

        queryCounter.reset();
        Page<PostSummaryResponse> page = postService.blogPosts("marco", PostListFilter.NONE, PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(10);
        // 블로그, 목록(카테고리 LEFT JOIN), 전체 수, 태그 일괄 조회
        assertThat(queryCounter.count()).isEqualTo(4);

        em.clear();
        queryCounter.reset();
        PostDetailResponse detail = postService.detail(target.getId(), null);
        // 글(블로그·주인·카테고리 fetch join), 태그, 이전, 다음, 트랙백 수(005)
        assertThat(queryCounter.count()).isEqualTo(5);
        assertThat(detail.author().nickname()).isEqualTo("marco");
        assertThat(detail.blogHandle()).isEqualTo("marco");
        assertThat(detail.prev()).isNotNull();
        assertThat(detail.next()).isNotNull();
    }

    @Test
    void previousAndNextAreListableNeighboursInTheSameBlog() {
        Post p1 = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0);
        persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PRIVATE, T0.plusSeconds(1));
        Post p3 = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0.plusSeconds(2));
        Post p4 = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0.plusSeconds(2));
        Blog other = persistBlog(persistUser("other", UserStatus.ACTIVE), "other");
        persistPost(other, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0.plusSeconds(1));
        flushAndClear();

        Long blogId = marcoBlog.getId();
        assertThat(postQueryRepository.findPrevious(blogId, p3.getId(), T0.plusSeconds(2)))
                .contains(new PostLink(p1.getId(), "글"));
        assertThat(postQueryRepository.findNext(blogId, p3.getId(), T0.plusSeconds(2)))
                .contains(new PostLink(p4.getId(), "글"));
        assertThat(postQueryRepository.findPrevious(blogId, p4.getId(), T0.plusSeconds(2)))
                .contains(new PostLink(p3.getId(), "글"));
        assertThat(postQueryRepository.findNext(blogId, p4.getId(), T0.plusSeconds(2))).isEmpty();
        assertThat(postQueryRepository.findPrevious(blogId, p1.getId(), T0)).isEmpty();
    }

    @Test
    void latestDraftIsNewestCopyOfTheBlogExcludingTrash() {
        Post a = persistPost(marcoBlog, PostStatus.DRAFT, PostVisibility.PUBLIC, null);
        Post b = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0);
        Post c = persistPost(marcoBlog, PostStatus.DRAFT, PostVisibility.PUBLIC, null);
        persistDraft(a, "A", T0.plusSeconds(10));
        persistDraft(b, "B", T0.plusSeconds(20));
        persistDraft(c, "C", T0.plusSeconds(30));
        c.moveToTrash(T0.plusSeconds(40));
        flushAndClear();

        assertThat(postQueryRepository.findLatestDraft(marcoBlog.getId()))
                .contains(new LatestDraftResponse(b.getId(), "B", T0.plusSeconds(20)));
        assertThat(postQueryRepository.findLatestDraft(-1L)).isEmpty();
    }

    @Test
    void draftCopyStoresTagsAsJson() {
        Post p = persistPost(marcoBlog, PostStatus.DRAFT, PostVisibility.PUBLIC, null);
        PostDraft draft = new PostDraft(p);
        draft.write("제목", "본문", 3L, List.of("spring", "jpa"), T0);
        postDraftRepository.save(draft);
        flushAndClear();

        PostDraft loaded = postDraftRepository.findById(p.getId()).orElseThrow();
        assertThat(loaded.getTags()).containsExactly("spring", "jpa");
        assertThat(loaded.getCategoryId()).isEqualTo(3L);
        assertThat(loaded.getPostId()).isEqualTo(p.getId());
    }

    @Test
    void findWithBlogAndOwnerIsOneQuery() {
        Post p = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0);
        flushAndClear();

        queryCounter.reset();
        Post loaded = postRepository.findWithBlogAndOwner(p.getId()).orElseThrow();
        assertThat(Hibernate.isInitialized(loaded.getBlog())).isTrue();
        assertThat(Hibernate.isInitialized(loaded.getBlog().getUser())).isTrue();
        assertThat(loaded.getBlog().getUser().getNickname()).isEqualTo("marco");
        assertThat(queryCounter.count()).isEqualTo(1);
    }

    @Test
    void viewCountIncrementIsSingleAtomicUpdateKeepingUpdatedAt() {
        Post p = persistPost(marcoBlog, PostStatus.PUBLISHED, PostVisibility.PUBLIC, T0);
        flushAndClear();
        Instant updatedAt = postRepository.findById(p.getId()).orElseThrow().getUpdatedAt();
        em.clear();

        queryCounter.reset();
        assertThat(postRepository.incrementViewCount(p.getId())).isEqualTo(1);
        assertThat(queryCounter.count()).isEqualTo(1);
        postRepository.incrementViewCount(p.getId());

        Post loaded = postRepository.findById(p.getId()).orElseThrow();
        assertThat(loaded.getViewCount()).isEqualTo(2);
        assertThat(loaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    private List<Long> idsMatching(BooleanExpression condition) {
        return queryFactory.select(post.id).from(post).join(post.blog, blog).join(blog.user, user)
                .where(condition).orderBy(post.id.asc()).fetch();
    }

    private User persistUser(String nickname, UserStatus status) {
        String hash = String.valueOf((char) ('a' + hashSeq++)).repeat(64);
        User u = new User(nickname + "@example.com", hash, "$2a$hash", nickname, null, null, "2026-10-06", T0);
        ReflectionTestUtils.setField(u, "status", status);
        em.persist(u);
        return u;
    }

    private Blog persistBlog(User owner, String handle) {
        Blog b = new Blog(owner, handle, Blog.defaultTitle(owner.getNickname()));
        em.persist(b);
        return b;
    }

    private Post persistPost(Blog b, PostStatus status, PostVisibility visibility, Instant publishedAt) {
        Post p = new Post(b, "글");
        if (status != PostStatus.DRAFT) {
            p.publish("글", "본문", "<p>본문</p>", "본문", "본문", null, visibility, true, publishedAt);
            p.applyProtection(visibility == PostVisibility.PROTECTED ? "$2a$hash" : null);
        }
        em.persist(p);
        return p;
    }

    private void persistDraft(Post p, String title, Instant savedAt) {
        PostDraft d = new PostDraft(p);
        d.write(title, "본문", null, List.of(), savedAt);
        em.persist(d);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
