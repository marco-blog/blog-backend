package net.java21.blog.backend.post.service;

import static net.java21.blog.backend.post.service.PostDraftServiceTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.repository.CategoryRepository;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.content.HtmlSanitizerPolicy;
import net.java21.blog.backend.content.MarkdownRenderer;
import net.java21.blog.backend.content.VideoEmbedTransformer;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PublishSettingsRequest;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import net.java21.blog.backend.post.repository.PostQueryRepository;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.tag.service.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 발행·수정 발행(T061, FR-013, FR-015, FR-070, FR-107·108, AS6·7). */
@ExtendWith(MockitoExtension.class)
class PostPublishServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final String KEY_A = "k3Jd9fQ2xLmA7pZ0bR5tYw";
    private static final String KEY_B = "AAAAAAAAAAAAAAAAAAAAAA";

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

    private PostPublishService service;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PostAccess access = new PostAccess(postRepository);
        CategoryAccess categoryAccess = new CategoryAccess(categoryRepository);
        PostService postService = new PostService(postRepository, postDraftRepository, postQueryRepository, access,
                new BlogAccess(blogRepository), categoryAccess, tagQueryRepository, clock);
        service = new PostPublishService(access, postDraftRepository,
                new MarkdownRenderer(new HtmlSanitizerPolicy(), new VideoEmbedTransformer()), postService,
                categoryAccess, tagService, clock);
        blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        post = TestEntities.post(100L, blog, "제목");
        lenient().when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
    }

    @Test
    void publishRendersSanitizesAppliesAndDeletesDraftCopy() {
        PostDraft draft = draft("  제목  ", "# 안녕\n\n<script>alert(1)</script>\n\n본문 ![a](/media/" + KEY_A + ")");
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        PostDetailResponse detail = service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));

        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.getTitle()).isEqualTo("제목");
        assertThat(post.getContentMarkdown()).startsWith("# 안녕");
        assertThat(post.getContentHtml()).contains("<h1>안녕</h1>").doesNotContain("script", "alert");
        assertThat(post.getContentText()).startsWith("안녕 본문");
        assertThat(post.getSummary()).isEqualTo(post.getContentText());
        assertThat(post.getThumbnailUrl()).isEqualTo("/media/" + KEY_A);
        assertThat(post.isCommentEnabled()).isTrue();
        assertThat(post.getPublishedAt()).isEqualTo(NOW);
        verify(postDraftRepository).delete(draft);
        assertThat(detail.id()).isEqualTo(100L);
        assertThat(detail.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(detail.contentMarkdown()).isNotNull();
        assertThat(detail.blogHandle()).isEqualTo("marco");
    }

    @Test
    void republishKeepsIdAndFirstPublishedAt() {
        Instant first = NOW.minusSeconds(86_400);
        post.publish("옛 제목", "옛 본문", "<p>옛 본문</p>", "옛 본문", "옛 본문", null, PostVisibility.PUBLIC, true, first);
        draft("새 제목", "새 본문");
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        PostDetailResponse detail = service.publish(1L, 100L, settings(PostVisibility.PRIVATE, null, false));

        assertThat(detail.id()).isEqualTo(100L);
        assertThat(post.getPublishedAt()).isEqualTo(first);
        assertThat(post.getTitle()).isEqualTo("새 제목");
        assertThat(post.getVisibility()).isEqualTo(PostVisibility.PRIVATE);
        assertThat(post.isCommentEnabled()).isFalse();
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
    }

    @Test
    void republishWithoutDraftUsesCurrentContent() {
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PRIVATE, true, NOW);
        when(postDraftRepository.findById(100L)).thenReturn(Optional.empty());
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));

        assertThat(post.getVisibility()).isEqualTo(PostVisibility.PUBLIC);
        assertThat(post.getContentHtml()).isEqualTo("<p>본문</p>\n");
        verify(postDraftRepository, never()).delete(any());
    }

    @Test
    void titleIsRequired() {
        draft("   ", "본문");

        assertThatThrownBy(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement()
                            .satisfies(f -> assertThat(f.field() + ":" + f.code()).isEqualTo("title:REQUIRED"));
                });
        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    void titleLongerThan200IsRejected() {
        draft("가".repeat(201), "본문");

        assertThatThrownBy(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors().getFirst().code())
                        .isEqualTo("TOO_LONG"));
    }

    @Test
    void emptyContentIsUnprocessable() {
        draft("제목", "  \n ");
        assertCode(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)),
                ErrorCode.POST_CONTENT_EMPTY);
        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    void chosenThumbnailMustBeAnImageInTheBody() {
        draft("제목", "![a](/media/" + KEY_A + ") ![b](/media/" + KEY_B + ")");
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, KEY_B, null));
        assertThat(post.getThumbnailUrl()).isEqualTo("/media/" + KEY_B);
    }

    @Test
    void thumbnailNotInBodyIsRejected() {
        draft("제목", "그림 없음");

        assertThatThrownBy(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, KEY_A, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors().getFirst().field())
                        .isEqualTo("thumbnailMediaKey"));
    }

    @Test
    void othersCannotPublish() {
        assertCode(() -> service.publish(2L, 100L, settings(PostVisibility.PUBLIC, null, null)), ErrorCode.FORBIDDEN);
    }

    @Test
    void trashedPostCannotBePublished() {
        post.moveToTrash(NOW);
        assertCode(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)),
                ErrorCode.POST_NOT_FOUND);
    }

    @Test
    void publishedPostNeverGoesBackToDraft() {
        post.publish("t", "b", "<p>b</p>", "b", "b", null, PostVisibility.PUBLIC, true, NOW);
        post.moveToTrash(NOW);
        post.restore();
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThatThrownBy(() -> TestEntities.post(1L, blog, "x").restore()).isInstanceOf(IllegalStateException.class);
    }

    private PostDraft draft(String title, String markdown) {
        PostDraft draft = new PostDraft(post);
        draft.write(title, markdown, null, null, NOW);
        when(postDraftRepository.findById(100L)).thenReturn(Optional.of(draft));
        return draft;
    }

    private static PublishSettingsRequest settings(PostVisibility visibility, String thumbnailKey, Boolean comments) {
        return new PublishSettingsRequest(visibility, thumbnailKey, comments, null, null);
    }
}
