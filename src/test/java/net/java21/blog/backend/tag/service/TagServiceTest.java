package net.java21.blog.backend.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.domain.PostTag;
import net.java21.blog.backend.tag.domain.Tag;
import net.java21.blog.backend.tag.domain.TagNormalizer;
import net.java21.blog.backend.tag.dto.BlogTagResponse;
import net.java21.blog.backend.tag.dto.TaggedPostSummaryResponse;
import net.java21.blog.backend.tag.repository.PostTagRepository;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.tag.repository.TagRepository;
import net.java21.blog.backend.tag.repository.TaggedPostRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 태그(T166, FR-025, AS3): 정규화(앞뒤 공백 제거 + 소문자, 단어 사이 공백 유지), 각 1~30자, 글당 최대 10개(넘으면 422
 * {@code TAG_LIMIT_EXCEEDED}), 중복 제거, 서비스 공통 태그 get-or-create(UNIQUE 경합 시 다시 조회).
 */
@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Mock
    private BlogRepository blogRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private PostTagRepository postTagRepository;
    @Mock
    private TagQueryRepository tagQueryRepository;
    @Mock
    private TagCreator tagCreator;

    private TagService service;

    @BeforeEach
    void setUp() {
        service = new TagService(new BlogAccess(blogRepository), tagRepository, postTagRepository,
                tagQueryRepository, tagCreator);
    }

    @Test
    void normalizeTrimsLowercasesAndKeepsInnerSpaces() {
        assertThat(TagNormalizer.normalize(" Spring Boot ")).isEqualTo("spring boot");
        assertThat(TagNormalizer.normalize(null)).isEmpty();
        assertThat(TagNormalizer.normalizeAll(List.of(" Spring Boot ", "JPA", "spring boot", "jpa "), "tags"))
                .containsExactly("spring boot", "jpa");
        assertThat(TagNormalizer.normalizeAll(null, "tags")).isEmpty();
    }

    @Test
    void eachTagIsOneToThirtyChars() {
        assertThat(TagNormalizer.normalizeAll(List.of("a".repeat(30)), "tags")).hasSize(1);
        assertThatThrownBy(() -> TagNormalizer.normalizeAll(List.of("a".repeat(31)), "tags"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(be.fieldErrors().getFirst().code()).isEqualTo("TOO_LONG");
                    assertThat(be.fieldErrors().getFirst().params()).containsEntry("max", 30);
                });
        assertThatThrownBy(() -> TagNormalizer.normalizeAll(List.of("ok", "  "), "tags"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).fieldErrors().getFirst().code()).isEqualTo("REQUIRED");
    }

    @Test
    void atMostTenDistinctTagsPerPost() {
        List<String> ten = IntStream.range(0, 10).mapToObj(i -> "t" + i).toList();
        assertThat(TagNormalizer.normalizeAll(ten, "tags")).hasSize(10);
        List<String> tenWithDuplicate = new ArrayList<>(ten);
        tenWithDuplicate.add("T0 ");
        assertThat(TagNormalizer.normalizeAll(tenWithDuplicate, "tags")).hasSize(10);
        List<String> eleven = new ArrayList<>(ten);
        eleven.add("t10");
        assertThatThrownBy(() -> TagNormalizer.normalizeAll(eleven, "tags"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.TAG_LIMIT_EXCEEDED);
    }

    @Test
    void resolveFindsExistingAndCreatesMissing() {
        Tag spring = tag(1L, "spring");
        when(tagRepository.findByNameIn(List.of("spring", "jpa"))).thenReturn(List.of(spring));
        when(tagCreator.insert("jpa")).thenReturn(2L);
        Tag jpa = tag(2L, "jpa");
        when(tagRepository.getReferenceById(2L)).thenReturn(jpa);

        assertThat(service.resolve(List.of("spring", "jpa"))).containsExactly(spring, jpa);
    }

    @Test
    void resolveRereadsWhenAnotherRequestCreatedTheSameTag() {
        when(tagRepository.findByNameIn(List.of("jpa"))).thenReturn(List.of());
        when(tagCreator.insert("jpa")).thenThrow(new DataIntegrityViolationException("uk_tags_name"));
        when(tagCreator.findId("jpa")).thenReturn(Optional.of(5L));
        Tag jpa = tag(5L, "jpa");
        when(tagRepository.getReferenceById(5L)).thenReturn(jpa);

        assertThat(service.resolve(List.of("jpa"))).containsExactly(jpa);
    }

    @Test
    void resolveRethrowsWhenTagStillMissingAndMergesCollationDuplicates() {
        when(tagRepository.findByNameIn(List.of("x"))).thenReturn(List.of());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other");
        when(tagCreator.insert("x")).thenThrow(failure);
        when(tagCreator.findId("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(List.of("x"))).isSameAs(failure);

        // MySQL 정렬 규칙상 "café"와 "cafe"는 같은 태그: 한 번만 연결한다.
        Tag cafe = tag(9L, "cafe");
        when(tagRepository.findByNameIn(List.of("cafe", "café"))).thenReturn(List.of(cafe));
        when(tagCreator.insert("café")).thenThrow(new DataIntegrityViolationException("uk"));
        when(tagCreator.findId("café")).thenReturn(Optional.of(9L));
        when(tagRepository.getReferenceById(9L)).thenReturn(cafe);
        assertThat(service.resolve(List.of("cafe", "café"))).containsExactly(cafe);

        assertThat(service.resolve(List.of())).isEmpty();
    }

    @Test
    void replacePostTagsDeletesThenLinks() {
        Blog blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        Post post = TestEntities.post(100L, blog, "글");
        Tag spring = tag(1L, "spring");
        when(tagRepository.findByNameIn(List.of("spring"))).thenReturn(List.of(spring));

        service.replacePostTags(post, List.of("spring"));

        InOrder order = inOrder(postTagRepository);
        order.verify(postTagRepository).deleteByPostId(100L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PostTag>> links = ArgumentCaptor.forClass(List.class);
        order.verify(postTagRepository).saveAll(links.capture());
        assertThat(links.getValue()).singleElement().satisfies(link -> {
            assertThat(link.getId().postId()).isEqualTo(100L);
            assertThat(link.getId().tagId()).isEqualTo(1L);
            assertThat(link.getPost()).isSameAs(post);
            assertThat(link.getTag()).isSameAs(spring);
            assertThat(link.isNew()).isTrue();
            assertThat(link.getCreatedAt()).isNull();
        });
    }

    @Test
    void replaceWithNoTagsOnlyDeletes() {
        Post post = TestEntities.post(100L, TestEntities.blog(10L, TestEntities.user(1L), "marco"), "글");

        service.replacePostTags(post, List.of());

        verify(postTagRepository).deleteByPostId(100L);
        verify(postTagRepository).saveAll(List.of());
        verify(tagRepository, never()).findByNameIn(anyCollection());
    }

    @Test
    void blogTagsOfVisibleBlog() {
        Blog blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(tagQueryRepository.findBlogTags(10L)).thenReturn(List.of(new BlogTagResponse("spring", 2)));

        assertThat(service.blogTags("marco")).containsExactly(new BlogTagResponse("spring", 2));
        when(blogRepository.findByHandleWithOwner("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.blogTags("gone")).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.BLOG_NOT_FOUND);
    }

    @Test
    void taggedPostsNormalizesNameAndFillsTagsAndHandle() {
        TaggedPostRow row = new TaggedPostRow(7L, "글", "요약", null, 3L, "Spring", 1, 0, PostVisibility.PUBLIC,
                PostStatus.PUBLISHED, NOW, NOW, "marco", false);
        TaggedPostRow untagged = new TaggedPostRow(8L, "글2", null, null, null, null, 0, 0, PostVisibility.PUBLIC,
                PostStatus.PUBLISHED, NOW, NOW, "other", false);
        when(tagQueryRepository.findTaggedPosts("spring boot", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(row, untagged), PageRequest.of(0, 20), 2));
        when(tagQueryRepository.findTagNames(List.of(7L, 8L))).thenReturn(Map.of(7L, List.of("spring boot")));

        Page<TaggedPostSummaryResponse> page = service.taggedPosts(" Spring Boot ", PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        TaggedPostSummaryResponse first = page.getContent().getFirst();
        assertThat(first.blogHandle()).isEqualTo("marco");
        assertThat(first.post().tags()).containsExactly("spring boot");
        assertThat(first.post().category().name()).isEqualTo("Spring");
        assertThat(first.post().hasDraft()).isFalse();
        assertThat(page.getContent().get(1).post().category()).isNull();
        assertThat(page.getContent().get(1).post().tags()).isEmpty();
    }

    @Test
    void taggedPostsWithImpossibleNameIsEmptyWithoutQuery() {
        assertThat(service.taggedPosts("  ", PageRequest.of(0, 20))).isEmpty();
        assertThat(service.taggedPosts("a".repeat(31), PageRequest.of(0, 20))).isEmpty();
        verifyNoInteractions(tagQueryRepository);
        verify(tagCreator, never()).insert(anyString());
        verify(postTagRepository, never()).saveAll(any());
    }

    private static Tag tag(long id, String name) {
        return TestEntities.with(new Tag(name), "id", id);
    }
}
