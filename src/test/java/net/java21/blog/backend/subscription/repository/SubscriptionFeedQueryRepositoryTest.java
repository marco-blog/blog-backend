package net.java21.blog.backend.subscription.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 구독 피드 조회(T019, FR-032, AS4, Edge Cases): 구독한 블로그의 "목록 노출 가능" 글만, 발행 최신순(같은 시각은 id 내림차순),
 * 페이지·전체 수, 블로그 handle·title과 작성자 닉네임·프로필, 쿼리 수가 구독 수·글 수와 무관(목록 1 + 수 1).
 */
@JpaRepositoryTest
@Import(SubscriptionFeedQueryRepository.class)
class SubscriptionFeedQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private SubscriptionFeedQueryRepository repository;
    @Autowired
    private BlogSubscriptionRepository subscriptions;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private User reader;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        reader = fx.user("reader");
    }

    @Test
    void onlyListablePostsOfSubscribedBlogsNewestFirst() {
        User marco = fx.user("marco");
        Blog a = fx.blog(marco, "marco");
        Blog c = fx.blog(fx.user("third"), "third");
        Blog notSubscribed = fx.blog(fx.user("nobody"), "nobody");
        Category spring = fx.category(a, null, "Spring", 0);
        Post a1 = fx.published(a, "A 첫 글", spring, 1);
        Post c1 = fx.published(c, "C 첫 글", null, 2);
        Post a2 = fx.published(a, "A 둘째 글", null, 3);
        Post sameTime = fx.published(c, "C 같은 시각", null, 3);
        fx.post(a, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 4);
        fx.post(a, "임시저장", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 5);
        fx.post(a, "휴지통", null, PostStatus.DELETED, PostVisibility.PUBLIC, 6);
        fx.published(notSubscribed, "구독 안 함", null, 7);
        fx.flushAndClear();
        subscribe(a);
        subscribe(c);

        Page<FeedPostRow> page = repository.findFeed(reader.getId(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(FeedPostRow::id)
                .containsExactly(Math.max(a2.getId(), sameTime.getId()), Math.min(a2.getId(), sameTime.getId()),
                        c1.getId(), a1.getId());
        FeedPostRow first = page.getContent().get(3);
        assertThat(first.title()).isEqualTo("A 첫 글");
        assertThat(first.blogHandle()).isEqualTo("marco");
        assertThat(first.blogTitle()).isEqualTo("marco 블로그");
        assertThat(first.authorNickname()).isEqualTo("marco");
        assertThat(first.categoryName()).isEqualTo("Spring");
        assertThat(first.summary()).isEqualTo("요약 A 첫 글");
        assertThat(first.visibility()).isEqualTo(PostVisibility.PUBLIC);
    }

    @Test
    void postsOfSuspendedWithdrawnAuthorsAndDeletedBlogsDisappear() {
        User suspended = fx.user("suspended");
        User withdrawn = fx.user("withdrawn");
        User active = fx.user("active");
        Blog s = fx.blog(suspended, "s");
        Blog w = fx.blog(withdrawn, "w");
        Blog deleted = fx.blog(active, "d");
        Blog alive = fx.blog(active, "alive");
        fx.published(s, "정지", null, 1);
        fx.published(w, "탈퇴", null, 2);
        fx.published(deleted, "삭제된 블로그", null, 3);
        Post visible = fx.published(alive, "보임", null, 4);
        TestEntities.with(suspended, "status", UserStatus.SUSPENDED);
        TestEntities.with(withdrawn, "status", UserStatus.WITHDRAWN);
        deleted.delete(NOW);
        fx.flushAndClear();
        List.of(s, w, deleted, alive).forEach(this::subscribe);

        Page<FeedPostRow> page = repository.findFeed(reader.getId(), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(FeedPostRow::id).containsExactly(visible.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void pagesWithTotalCount() {
        Blog a = fx.blog(fx.user("marco"), "marco");
        for (int i = 0; i < 5; i++) {
            fx.published(a, "글 " + i, null, i);
        }
        fx.flushAndClear();
        subscribe(a);

        Page<FeedPostRow> second = repository.findFeed(reader.getId(), PageRequest.of(1, 2));

        assertThat(second.getTotalElements()).isEqualTo(5);
        assertThat(second.getContent()).extracting(FeedPostRow::title).containsExactly("글 2", "글 1");
    }

    @Test
    void emptyWhenNothingIsSubscribed() {
        fx.published(fx.blog(fx.user("marco"), "marco"), "글", null, 1);
        fx.flushAndClear();

        Page<FeedPostRow> page = repository.findFeed(reader.getId(), PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 12})
    void twoQueriesRegardlessOfSubscriptionsAndPosts(int blogs) {
        for (int i = 0; i < blogs; i++) {
            User owner = fx.user("o" + i);
            Media media = new Media(owner, "k" + String.format("%021d", i), MediaPurpose.PROFILE, "p.png",
                    "2026/10/p.png", "image/png", 10, 1, 1);
            TestEntities.with(media, "status", MediaStatus.ATTACHED);
            em.persist(media);
            owner.changeProfileMedia(media);
            Blog b = fx.blog(owner, "b" + i);
            fx.published(b, "글 " + i, fx.category(b, null, "c" + i, 0), i);
            fx.published(b, "또 " + i, null, i + 100);
            em.flush();
            subscriptions.insertIgnore(reader.getId(), b.getId(), NOW);
        }
        fx.flushAndClear();
        queryCounter.reset();

        Page<FeedPostRow> page = repository.findFeed(reader.getId(), PageRequest.of(0, 50));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(blogs * 2);
        assertThat(page.getContent()).allSatisfy(row -> assertThat(row.authorProfileMediaKey()).startsWith("k"));
    }

    private void subscribe(Blog blog) {
        subscriptions.insertIgnore(reader.getId(), blog.getId(), NOW);
    }
}
