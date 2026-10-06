package net.java21.blog.backend.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.post.repository.PostSummaryRow;
import net.java21.blog.backend.post.repository.RelatedPostQueryRepository;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 관련 글 서비스(T074, FR-068): 기준 글을 볼 수 있어야 하고(아니면 404 {@code POST_NOT_FOUND}), 같은 블로그·기준 글의 카테고리로 최대 5편을 찾아
 * 태그를 한 번에 읽는다.
 */
@ExtendWith(MockitoExtension.class)
class RelatedPostServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private PostRepository postRepository;
    @Mock
    private RelatedPostQueryRepository relatedRepository;
    @Mock
    private TagQueryRepository tagQueryRepository;

    private RelatedPostService service;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        service = new RelatedPostService(postRepository, relatedRepository, tagQueryRepository);
        User owner = TestEntities.user(1L);
        blog = TestEntities.blog(10L, owner, "marco");
        post = TestEntities.post(100L, blog, "기준");
        Category category = TestEntities.with(new Category(blog, null, "Spring", 0), "id", 7L);
        post.classify(category);
    }

    @Test
    void relatedPostsWithTagsLoadedOnce() {
        post.publish("기준", "md", "<p>html</p>", "text", "요약", null, PostVisibility.PUBLIC, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(relatedRepository.findRelated(10L, 100L, 7L, RelatedPostService.LIMIT))
                .thenReturn(List.of(row(101L), row(102L)));
        when(tagQueryRepository.findTagNames(List.of(101L, 102L))).thenReturn(Map.of(101L, List.of("java")));

        List<PostSummaryResponse> related = service.related(100L, null);

        assertThat(related).extracting(PostSummaryResponse::id).containsExactly(101L, 102L);
        assertThat(related.getFirst().tags()).containsExactly("java");
        assertThat(related.get(1).tags()).isEmpty();
        assertThat(related).allSatisfy(r -> assertThat(r.hasDraft()).isFalse());
        verify(tagQueryRepository, times(1)).findTagNames(any());
    }

    @Test
    void uncategorizedBasePassesNullCategory() {
        post.classify(null);
        post.publish("기준", "md", "<p>html</p>", "text", "요약", null, PostVisibility.PUBLIC, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(relatedRepository.findRelated(eq(10L), eq(100L), eq(null), anyInt())).thenReturn(List.of());
        when(tagQueryRepository.findTagNames(List.of())).thenReturn(Map.of());

        assertThat(service.related(100L, 5L)).isEmpty();
    }

    @Test
    void ownerCanAskForRelatedPostsOfAPrivatePost() {
        post.publish("기준", "md", "<p>html</p>", "text", "요약", null, PostVisibility.PRIVATE, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(relatedRepository.findRelated(anyLong(), anyLong(), any(), anyInt())).thenReturn(List.of());
        when(tagQueryRepository.findTagNames(List.of())).thenReturn(Map.of());

        assertThat(service.related(100L, 1L)).isEmpty();
    }

    @Test
    void invisibleOrMissingBaseIs404() {
        post.publish("기준", "md", "<p>html</p>", "text", "요약", null, PostVisibility.PRIVATE, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(postRepository.findWithBlogAndOwner(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.related(100L, 2L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
        assertThatThrownBy(() -> service.related(100L, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
        assertThatThrownBy(() -> service.related(999L, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
        verify(relatedRepository, never()).findRelated(anyLong(), anyLong(), any(), anyInt());
        verifyNoInteractions(tagQueryRepository);
    }

    private static PostSummaryRow row(Long id) {
        return new PostSummaryRow(id, "글 " + id, "요약", null, 7L, "Spring", 1, 0, PostVisibility.PUBLIC,
                PostStatus.PUBLISHED, NOW, NOW);
    }
}
