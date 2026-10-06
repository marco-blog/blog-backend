package net.java21.blog.backend.post.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.like.repository.PostLikeRepository;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.dto.CategoryRef;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PostLink;
import net.java21.blog.backend.post.dto.PostListFilter;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostQueryRepository;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.post.repository.PostSummaryRow;
import net.java21.blog.backend.tag.domain.TagNormalizer;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 글 상세·블로그 글 목록·휴지통(FR-011, FR-017~019, FR-084). 노출은 data-model "글 노출 매트릭스"를 {@link PostExposure}로 판단한다.
 * 볼 수 없는 글은 "없는 글"과 같은 404 {@code POST_NOT_FOUND}다.
 */
@Service
public class PostService {

    private final PostRepository postRepository;
    private final PostDraftRepository postDraftRepository;
    private final PostQueryRepository postQueryRepository;
    private final PostAccess postAccess;
    private final BlogAccess blogAccess;
    private final CategoryAccess categoryAccess;
    private final TagQueryRepository tagQueryRepository;
    private final PostLikeRepository postLikeRepository;
    private final Clock clock;

    public PostService(PostRepository postRepository, PostDraftRepository postDraftRepository,
            PostQueryRepository postQueryRepository, PostAccess postAccess, BlogAccess blogAccess,
            CategoryAccess categoryAccess, TagQueryRepository tagQueryRepository,
            PostLikeRepository postLikeRepository, Clock clock) {
        this.postRepository = postRepository;
        this.postDraftRepository = postDraftRepository;
        this.postQueryRepository = postQueryRepository;
        this.postAccess = postAccess;
        this.blogAccess = blogAccess;
        this.categoryAccess = categoryAccess;
        this.tagQueryRepository = tagQueryRepository;
        this.postLikeRepository = postLikeRepository;
        this.clock = clock;
    }

    /**
     * 블로그 글 목록(목록 노출 가능 글만, 발행 최신순, FR-026). 카테고리는 이 블로그의 것이어야 하고(아니면 404 {@code CATEGORY_NOT_FOUND}),
     * 상위 카테고리는 하위 글을 포함한다. 태그 이름은 정규화해 비교한다.
     * 쿼리 4회(블로그, 목록, 전체 수, 태그 일괄 조회) + 카테고리 조건이 있으면 확인 1회. 글 수와 무관하다.
     */
    @Transactional(readOnly = true)
    public Page<PostSummaryResponse> blogPosts(String handle, PostListFilter filter, Pageable pageable) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        if (filter.categoryId() != null) {
            categoryAccess.requireInBlog(blog.getId(), filter.categoryId());
        }
        String tag = filter.tag() == null ? null : TagNormalizer.normalize(filter.tag());
        PostListFilter normalized = new PostListFilter(filter.categoryId(), tag == null || tag.isEmpty() ? null : tag);
        Page<PostSummaryRow> rows = postQueryRepository.findListablePosts(blog.getId(), normalized, pageable);
        Map<Long, List<String>> tags = tagQueryRepository.findTagNames(
                rows.getContent().stream().map(PostSummaryRow::id).toList());
        return rows.map(row -> row.toPublicResponse(tags.get(row.id())));
    }

    /**
     * 글 상세. 주인 외에게는 본문 노출 가능 글만, 주인에게는 DRAFT·PRIVATE도 보인다(DELETED는 상세에서 404).
     * 쿼리: 글(블로그·주인·카테고리 fetch join) 1회 + 태그 1회 + 발행된 글이면 이전·다음 2회 + 로그인했으면 좋아요 여부 1회(002).
     */
    @Transactional(readOnly = true)
    public PostDetailResponse detail(Long postId, Long viewerId) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> PostExposure.isDetailVisibleTo(p, viewerId))
                .orElseThrow(() -> PostAccess.notFound(postId));
        return detailOf(post, viewerId);
    }

    /**
     * 이미 읽은 글(블로그·주인 포함)을 이 사람({@code viewerId}, 비로그인 null)에게 보여줄 상세 응답. {@code contentMarkdown}은 주인에게만,
     * {@code likedByMe}는 비로그인이면 null.
     */
    PostDetailResponse detailOf(Post post, Long viewerId) {
        boolean owner = post.isOwnedBy(viewerId);
        PostLink prev = null;
        PostLink next = null;
        if (post.getPublishedAt() != null && post.isPublished()) {
            Long blogId = post.getBlog().getId();
            prev = postQueryRepository.findPrevious(blogId, post.getId(), post.getPublishedAt()).orElse(null);
            next = postQueryRepository.findNext(blogId, post.getId(), post.getPublishedAt()).orElse(null);
        }
        Boolean likedByMe = viewerId == null ? null
                : postLikeRepository.existsByUserIdAndPostId(viewerId, post.getId());
        return PostDetailResponse.of(post, owner, prev, next, tagNames(post.getId()), likedByMe);
    }

    /** 휴지통으로(FR-084). 이미 휴지통이면 404(상세와 같다). 남의 글 403. */
    @Transactional
    public void delete(long userId, Long postId) {
        postAccess.requireOwnedEditablePost(postId, userId).moveToTrash(clock.instant());
    }

    /** 휴지통에서 삭제 전 상태로(FR-084). 휴지통 글이 아니면 422 {@code POST_NOT_IN_TRASH}. */
    @Transactional
    public PostSummaryResponse restore(long userId, Long postId) {
        Post post = postAccess.requireOwnedPost(postId, userId);
        if (!post.isDeleted()) {
            throw new BusinessException(ErrorCode.POST_NOT_IN_TRASH, "Post is not in trash: " + postId);
        }
        post.restore();
        postRepository.flush();
        return new PostSummaryResponse(post.getId(), post.getTitle(), post.getSummary(), post.getThumbnailUrl(),
                CategoryRef.of(post.getCategory()), tagNames(postId), post.getViewCount(), post.getCommentCount(),
                post.getVisibility(), post.getStatus(),
                post.getPublishedAt(), post.getUpdatedAt(), postDraftRepository.existsById(postId), null, null);
    }

    /** 글 하나의 태그 이름(이름순). 쿼리 1회. */
    List<String> tagNames(Long postId) {
        return tagQueryRepository.findTagNames(List.of(postId)).getOrDefault(postId, List.of());
    }
}
