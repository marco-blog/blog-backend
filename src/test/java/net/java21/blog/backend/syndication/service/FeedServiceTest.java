package net.java21.blog.backend.syndication.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.FeedContentMode;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.category.repository.CategoryRepository;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.syndication.repository.FeedItemQueryRepository;
import net.java21.blog.backend.syndication.repository.FeedItemRow;
import net.java21.blog.backend.syndication.repository.FeedVersionRow;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 블로그 피드 내용(T084, FR-044~047, research D6): FULL은 본문 HTML(절대 주소), SUMMARY는 요약, 본문 노출 가능이 아닌 글은 제목·링크·시각만,
 * 카테고리 피드 제목 {@code {카테고리} - {블로그}}, 소개가 없으면 description=제목, ETag는 같은 입력에서 같고 글 수정·비공개 전환·피드 설정·
 * 블로그 제목 변경에서 바뀜, 없는 블로그·삭제·주인 정지·탈퇴 404 {@code BLOG_NOT_FOUND}, 다른 블로그 카테고리 404 {@code CATEGORY_NOT_FOUND}.
 */
@ExtendWith(MockitoExtension.class)
class FeedServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant BLOG_UPDATED = Instant.parse("2026-09-01T00:00:00Z");

    @Mock
    private BlogRepository blogRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private FeedItemQueryRepository itemRepository;
    @Mock
    private TagQueryRepository tagQueryRepository;

    private FeedService service;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        SiteProperties site = new SiteProperties("https://blog.java21.net");
        service = new FeedService(new BlogAccess(blogRepository), new CategoryAccess(categoryRepository),
                itemRepository, tagQueryRepository, new FeedContentUrlRewriter(site), site);
        owner = TestEntities.user(1L, "marco@example.com", "{hash}", "마르코");
        blog = TestEntities.blog(10L, owner, "marco");
        blog.changeTitle("마르코의 블로그");
        blog.changeDescription("자바 이야기");
        TestEntities.with(blog, "updatedAt", BLOG_UPDATED);
        lenient().when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
    }

    @Test
    void fullFeedHasAbsoluteBodyHtmlAndCategories() {
        givenVersions(null, version(2L, 5), version(1L, 3));
        when(itemRepository.findItems(10L, null, 20)).thenReturn(List.of(
                item(2L, "둘째 글", PostVisibility.PUBLIC, "Spring"), item(1L, "첫 글", PostVisibility.PUBLIC, null)));
        when(tagQueryRepository.findTagNames(List.of(2L, 1L))).thenReturn(Map.of(2L, List.of("java", "jpa")));

        FeedPlan plan = service.plan("marco", null);
        FeedSnapshot feed = service.snapshot(plan);

        assertThat(feed.title()).isEqualTo("마르코의 블로그");
        assertThat(feed.link()).isEqualTo("https://blog.java21.net/marco");
        assertThat(feed.description()).isEqualTo("자바 이야기");
        assertThat(feed.rssUrl()).isEqualTo("https://blog.java21.net/marco/rss");
        assertThat(feed.atomUrl()).isEqualTo("https://blog.java21.net/marco/atom");
        assertThat(feed.author()).isEqualTo("마르코");
        assertThat(feed.updated()).isEqualTo(T0.plusSeconds(5 * 60));
        assertThat(plan.lastModified()).isEqualTo(T0.plusSeconds(5 * 60));
        FeedEntry first = feed.entries().getFirst();
        assertThat(first.title()).isEqualTo("둘째 글");
        assertThat(first.link()).isEqualTo("https://blog.java21.net/marco/2");
        assertThat(first.contentHtml()).isEqualTo("<p><img src=\"https://blog.java21.net/media/k/600x400\"></p>");
        assertThat(first.summary()).isNull();
        assertThat(first.categories()).containsExactly("Spring", "java", "jpa");
        assertThat(first.published()).isEqualTo(T0);
        assertThat(first.updated()).isEqualTo(T0.plusSeconds(60));
        assertThat(feed.entries().get(1).categories()).isEmpty();
    }

    @Test
    void summaryModeUsesSummaryAndItemCountIsTheLimit() {
        blog.changeFeedSettings(10, FeedContentMode.SUMMARY);
        when(itemRepository.findVersions(10L, null, 10)).thenReturn(List.of(version(1L, 1)));
        when(itemRepository.findItems(10L, null, 10)).thenReturn(List.of(item(1L, "글", PostVisibility.PUBLIC, null)));
        when(tagQueryRepository.findTagNames(List.of(1L))).thenReturn(Map.of());

        FeedEntry entry = service.snapshot(service.plan("marco", null)).entries().getFirst();

        assertThat(entry.contentHtml()).isNull();
        assertThat(entry.summary()).isEqualTo("요약 1");
    }

    @Test
    void postsWithoutVisibleBodyGetOnlyTitleLinkAndTimes() {
        givenVersions(null, version(1L, 1));
        when(itemRepository.findItems(10L, null, 20))
                .thenReturn(List.of(item(1L, "보호 글", PostVisibility.PRIVATE, "Spring")));
        when(tagQueryRepository.findTagNames(List.of(1L))).thenReturn(Map.of(1L, List.of("java")));

        FeedEntry entry = service.snapshot(service.plan("marco", null)).entries().getFirst();

        assertThat(entry.title()).isEqualTo("보호 글");
        assertThat(entry.link()).isEqualTo("https://blog.java21.net/marco/1");
        assertThat(entry.contentHtml()).isNull();
        assertThat(entry.summary()).isNull();
        assertThat(entry.categories()).isEmpty();
        assertThat(entry.published()).isNotNull();
    }

    @Test
    void categoryFeedIsNamedAfterCategoryAndBlog() {
        Category spring = TestEntities.with(new Category(blog, null, "Spring", 0), "id", 7L);
        TestEntities.with(spring, "updatedAt", BLOG_UPDATED);
        when(categoryRepository.findByIdAndBlogId(7L, 10L)).thenReturn(Optional.of(spring));
        givenVersions(7L);
        when(itemRepository.findItems(10L, 7L, 20)).thenReturn(List.of());
        when(tagQueryRepository.findTagNames(List.of())).thenReturn(Map.of());

        FeedPlan plan = service.plan("marco", 7L);
        FeedSnapshot feed = service.snapshot(plan);

        assertThat(feed.title()).isEqualTo("Spring - 마르코의 블로그");
        assertThat(feed.link()).isEqualTo("https://blog.java21.net/marco/category/7");
        assertThat(feed.rssUrl()).isEqualTo("https://blog.java21.net/marco/category/7/rss");
        assertThat(feed.atomUrl()).isNull();
        assertThat(feed.entries()).isEmpty();
        // 글이 없으면 블로그·카테고리 수정 시각
        assertThat(feed.updated()).isEqualTo(BLOG_UPDATED);
    }

    @Test
    void descriptionFallsBackToTitle() {
        blog.changeDescription(null);
        givenVersions(null);
        when(itemRepository.findItems(10L, null, 20)).thenReturn(List.of());
        when(tagQueryRepository.findTagNames(List.of())).thenReturn(Map.of());

        assertThat(service.snapshot(service.plan("marco", null)).description()).isEqualTo("마르코의 블로그");
    }

    @Test
    void etagIsStableAndWeak() {
        givenVersions(null, version(2L, 5), version(1L, 3));

        String etag = service.plan("marco", null).etag();

        assertThat(etag).startsWith("W/\"").endsWith("\"");
        assertThat(service.plan("marco", null).etag()).isEqualTo(etag);
    }

    @Test
    void etagChangesWhenAPostIsEditedOrLeavesTheFeed() {
        givenVersions(null, version(2L, 5), version(1L, 3));
        String before = service.plan("marco", null).etag();

        givenVersions(null, version(2L, 6), version(1L, 3));
        String edited = service.plan("marco", null).etag();
        givenVersions(null, version(1L, 3));
        String madePrivate = service.plan("marco", null).etag();

        assertThat(edited).isNotEqualTo(before);
        assertThat(madePrivate).isNotEqualTo(before).isNotEqualTo(edited);
    }

    @Test
    void etagChangesWithFeedSettingsAndBlogTitle() {
        when(itemRepository.findVersions(eq(10L), isNull(), anyInt())).thenReturn(List.of(version(1L, 3)));
        String before = service.plan("marco", null).etag();

        blog.changeFeedSettings(20, FeedContentMode.SUMMARY);
        String summary = service.plan("marco", null).etag();
        blog.changeTitle("새 제목");
        String retitled = service.plan("marco", null).etag();

        assertThat(summary).isNotEqualTo(before);
        assertThat(retitled).isNotEqualTo(summary);
    }

    @Test
    void missingDeletedOrSuspendedBlogsAre404() {
        when(blogRepository.findByHandleWithOwner("nobody")).thenReturn(Optional.empty());
        assertBlogNotFound("nobody");

        blog.delete(T0);
        assertBlogNotFound("marco");

        Blog suspendedBlog = TestEntities.blog(11L, TestEntities.with(TestEntities.user(2L), "status",
                UserStatus.SUSPENDED), "s");
        when(blogRepository.findByHandleWithOwner("s")).thenReturn(Optional.of(suspendedBlog));
        assertBlogNotFound("s");
        Blog withdrawnBlog = TestEntities.blog(12L, TestEntities.with(TestEntities.user(3L), "status",
                UserStatus.WITHDRAWN), "w");
        when(blogRepository.findByHandleWithOwner("w")).thenReturn(Optional.of(withdrawnBlog));
        assertBlogNotFound("w");
    }

    @Test
    void categoryOfAnotherBlogIs404() {
        when(categoryRepository.findByIdAndBlogId(99L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.plan("marco", 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND));
    }

    private void assertBlogNotFound(String handle) {
        assertThatThrownBy(() -> service.plan(handle, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BLOG_NOT_FOUND));
    }

    private void givenVersions(Long categoryId, FeedVersionRow... rows) {
        when(itemRepository.findVersions(10L, categoryId, blog.getFeedItemCount())).thenReturn(List.of(rows));
    }

    private static FeedVersionRow version(Long id, int minutes) {
        return new FeedVersionRow(id, T0.plusSeconds(60L * minutes));
    }

    private static FeedItemRow item(Long id, String title, PostVisibility visibility, String categoryName) {
        return new FeedItemRow(id, title, "<p><img src=\"/media/k/600x400\"></p>", "요약 " + id, visibility, T0,
                T0.plusSeconds(60), categoryName);
    }
}
