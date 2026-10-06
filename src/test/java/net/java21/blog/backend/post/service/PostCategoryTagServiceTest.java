package net.java21.blog.backend.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.category.repository.CategoryRepository;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.content.HtmlSanitizerPolicy;
import net.java21.blog.backend.content.MarkdownRenderer;
import net.java21.blog.backend.content.VideoEmbedTransformer;
import net.java21.blog.backend.manage.dto.BulkAction;
import net.java21.blog.backend.manage.dto.BulkPostRequest;
import net.java21.blog.backend.manage.repository.ManagePostQueryRepository;
import net.java21.blog.backend.manage.service.ManagePostService;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.CategoryRef;
import net.java21.blog.backend.post.dto.DraftResponse;
import net.java21.blog.backend.post.dto.DraftWriteRequest;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PostListFilter;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.post.dto.PublishSettingsRequest;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import net.java21.blog.backend.post.repository.PostQueryRepository;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.post.repository.PostSummaryRow;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.tag.service.TagService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 글의 카테고리·태그(T169, FR-024·025, AS3·4): 임시저장은 {@code categoryId}·{@code tags}를 사본에 저장, 발행 시 같은 블로그 카테고리가
 * 아니면 404 {@code CATEGORY_NOT_FOUND}, {@code post_tags} 교체, PostDetail·PostSummary의 {@code category}·{@code tags},
 * 일괄 작업 {@code MOVE_CATEGORY}.
 */
@ExtendWith(MockitoExtension.class)
class PostCategoryTagServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private net.java21.blog.backend.media.service.MediaReferenceService mediaReferences;
    @Mock
    private PostRepository postRepository;
    @Mock
    private PostDraftRepository postDraftRepository;
    @Mock
    private PostQueryRepository postQueryRepository;
    @Mock
    private BlogRepository blogRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private TagQueryRepository tagQueryRepository;
    @Mock
    private TagService tagService;
    @Mock
    private ManagePostQueryRepository managePostQueryRepository;

    private PostPublishService publishService;
    private PostDraftService draftService;
    private PostService postService;
    private ManagePostService managePostService;
    private Blog blog;
    private Post post;
    private Category spring;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PostAccess access = new PostAccess(postRepository);
        BlogAccess blogAccess = new BlogAccess(blogRepository);
        CategoryAccess categoryAccess = new CategoryAccess(categoryRepository);
        postService = new PostService(postRepository, postDraftRepository, postQueryRepository, access, blogAccess,
                categoryAccess, tagQueryRepository, clock);
        publishService = new PostPublishService(access, postDraftRepository,
                new MarkdownRenderer(new HtmlSanitizerPolicy(), new VideoEmbedTransformer()), postService,
                categoryAccess, tagService, mediaReferences, clock);
        draftService = new PostDraftService(blogAccess, access, postRepository, postDraftRepository,
                postQueryRepository, tagQueryRepository, mediaReferences, clock);
        managePostService = new ManagePostService(blogAccess, managePostQueryRepository, categoryAccess,
                tagQueryRepository, new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 500), clock);
        blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        post = TestEntities.post(100L, blog, "제목");
        spring = TestEntities.with(new Category(blog, null, "Spring", 0), "id", 12L);
        lenient().when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        lenient().when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        lenient().when(categoryRepository.findByIdAndBlogId(12L, 10L)).thenReturn(Optional.of(spring));
        lenient().when(categoryRepository.findByIdAndBlogId(eq(99L), anyLong())).thenReturn(Optional.empty());
        lenient().when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        lenient().when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
    }

    @Test
    void draftSaveKeepsCategoryAndRawTagsWithoutValidation() {
        when(postDraftRepository.findById(100L)).thenReturn(Optional.empty());

        draftService.save(1L, 100L, new DraftWriteRequest("제목", "본문", 99L, List.of(" Spring ", "")));

        ArgumentCaptor<PostDraft> saved = ArgumentCaptor.forClass(PostDraft.class);
        verify(postDraftRepository).save(saved.capture());
        assertThat(saved.getValue().getCategoryId()).isEqualTo(99L);
        assertThat(saved.getValue().getTags()).containsExactly(" Spring ", "");
    }

    @Test
    void publishAppliesDraftCategoryAndNormalizedTags() {
        draft(12L, List.of(" Spring Boot ", "JPA", "jpa"));
        when(tagQueryRepository.findTagNames(List.of(100L))).thenReturn(Map.of(100L, List.of("jpa", "spring boot")));

        PostDetailResponse detail = publishService.publish(1L, 100L, settings(null, null));

        assertThat(post.getCategory()).isSameAs(spring);
        verify(tagService).replacePostTags(post, List.of("spring boot", "jpa"));
        assertThat(detail.category()).isEqualTo(new CategoryRef(12L, "Spring"));
        assertThat(detail.tags()).containsExactly("jpa", "spring boot");
    }

    @Test
    void publishSettingsOverrideDraftValues() {
        Category life = TestEntities.with(new Category(blog, null, "일상", 1), "id", 13L);
        when(categoryRepository.findByIdAndBlogId(13L, 10L)).thenReturn(Optional.of(life));
        draft(12L, List.of("old"));

        publishService.publish(1L, 100L, settings(13L, List.of("New")));

        assertThat(post.getCategory()).isSameAs(life);
        verify(tagService).replacePostTags(post, List.of("new"));
    }

    @Test
    void publishWithoutDraftKeepsCurrentCategoryAndTags() {
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW);
        post.classify(spring);
        when(postDraftRepository.findById(100L)).thenReturn(Optional.empty());

        publishService.publish(1L, 100L, settings(null, null));

        assertThat(post.getCategory()).isSameAs(spring);
        verify(tagService, never()).replacePostTags(any(), any());
    }

    @Test
    void draftWithoutCategoryMakesPostUncategorized() {
        post.classify(spring);
        draft(null, List.of());

        publishService.publish(1L, 100L, settings(null, null));

        assertThat(post.getCategory()).isNull();
        verify(tagService).replacePostTags(post, List.of());
    }

    @Test
    void otherBlogsCategoryIs404AndNothingChanges() {
        draft(99L, List.of("spring"));

        assertCode(() -> publishService.publish(1L, 100L, settings(null, null)), ErrorCode.CATEGORY_NOT_FOUND);
        assertThat(post.isPublished()).isFalse();
        verify(tagService, never()).replacePostTags(any(), any());
    }

    @Test
    void eleventhTagIsRejectedBeforePublishing() {
        draft(null, IntStream.range(0, 11).mapToObj(i -> "t" + i).toList());

        assertCode(() -> publishService.publish(1L, 100L, settings(null, null)), ErrorCode.TAG_LIMIT_EXCEEDED);
        assertThat(post.isPublished()).isFalse();
        verify(tagService, never()).replacePostTags(any(), any());
    }

    @Test
    void draftLoadWithoutCopyReturnsPublishedCategoryAndTags() {
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW);
        post.classify(spring);
        when(postDraftRepository.findById(100L)).thenReturn(Optional.empty());
        when(tagQueryRepository.findTagNames(List.of(100L))).thenReturn(Map.of(100L, List.of("spring")));

        DraftResponse draft = draftService.get(1L, 100L);

        assertThat(draft.categoryId()).isEqualTo(12L);
        assertThat(draft.tags()).containsExactly("spring");
    }

    @Test
    void blogPostsChecksCategoryNormalizesTagAndFillsTags() {
        var row = new PostSummaryRow(100L, "제목", "요약", null, 12L,
                "Spring", 0, 0, PostVisibility.PUBLIC, PostStatus.PUBLISHED,
                NOW, NOW);
        when(postQueryRepository.findListablePosts(10L, new PostListFilter(12L, "spring boot"), PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(tagQueryRepository.findTagNames(List.of(100L))).thenReturn(Map.of(100L, List.of("spring boot")));

        PostSummaryResponse item = postService.blogPosts("marco", new PostListFilter(12L, " Spring Boot "),
                PageRequest.of(0, 20)).getContent().getFirst();

        assertThat(item.category()).isEqualTo(new CategoryRef(12L, "Spring"));
        assertThat(item.tags()).containsExactly("spring boot");
        assertCode(() -> postService.blogPosts("marco", new PostListFilter(99L, null), PageRequest.of(0, 20)),
                ErrorCode.CATEGORY_NOT_FOUND);
        when(postQueryRepository.findListablePosts(10L, PostListFilter.NONE, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of()));
        assertThat(postService.blogPosts("marco", new PostListFilter(null, "  "), PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void restoreReturnsCategoryAndTags() {
        post.classify(spring);
        post.moveToTrash(NOW);
        when(tagQueryRepository.findTagNames(List.of(100L))).thenReturn(Map.of(100L, List.of("jpa")));

        PostSummaryResponse restored = postService.restore(1L, 100L);

        assertThat(restored.category()).isEqualTo(new CategoryRef(12L, "Spring"));
        assertThat(restored.tags()).containsExactly("jpa");
    }

    @Test
    void bulkMoveCategoryToSameBlogsCategory() {
        when(managePostQueryRepository.countOwned(10L, List.of(1L, 2L))).thenReturn(2L);
        when(managePostQueryRepository.moveCategory(10L, List.of(1L, 2L), spring, NOW)).thenReturn(2L);

        assertThat(managePostService.bulk(1L, "marco",
                new BulkPostRequest(List.of(1L, 2L, 1L), BulkAction.MOVE_CATEGORY, null, 12L)).updated())
                .isEqualTo(2);
    }

    @Test
    void bulkMoveWithoutCategoryMakesUncategorized() {
        when(managePostQueryRepository.countOwned(10L, List.of(3L))).thenReturn(1L);
        when(managePostQueryRepository.moveCategory(10L, List.of(3L), null, NOW)).thenReturn(1L);

        assertThat(managePostService.bulk(1L, "marco",
                new BulkPostRequest(List.of(3L), BulkAction.MOVE_CATEGORY, null, null)).updated()).isEqualTo(1);
    }

    @Test
    void bulkMoveRejectsOtherBlogsCategoryAndOtherBlogsPosts() {
        assertCode(() -> managePostService.bulk(1L, "marco",
                new BulkPostRequest(List.of(1L), BulkAction.MOVE_CATEGORY, null, 99L)), ErrorCode.CATEGORY_NOT_FOUND);
        when(managePostQueryRepository.countOwned(10L, List.of(1L, 77L))).thenReturn(1L);
        assertCode(() -> managePostService.bulk(1L, "marco",
                new BulkPostRequest(List.of(1L, 77L), BulkAction.MOVE_CATEGORY, null, 12L)), ErrorCode.FORBIDDEN);
        verify(managePostQueryRepository, never()).moveCategory(anyLong(), anyCollection(), any(), any());
    }

    private void draft(Long categoryId, List<String> tags) {
        PostDraft draft = new PostDraft(post);
        draft.write("제목", "본문", categoryId, tags, NOW);
        when(postDraftRepository.findById(100L)).thenReturn(Optional.of(draft));
    }

    private static PublishSettingsRequest settings(Long categoryId, List<String> tags) {
        return new PublishSettingsRequest(PostVisibility.PUBLIC, null, null, categoryId, tags);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(code);
    }
}
