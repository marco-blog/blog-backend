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
import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.tag.domain.TagNormalizer;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.trackback.TrackbackUrls;
import net.java21.blog.backend.trackback.TrackbackVisibility;
import net.java21.blog.backend.trackback.repository.TrackbackQueryRepository;
import net.java21.blog.backend.trackback.service.TrackbackSendService;
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
    private final TrackbackQueryRepository trackbackQueryRepository;
    private final TrackbackUrls trackbackUrls;
    private final TrackbackSendService trackbackSendService;
    private final Clock clock;
    private final BlogCalendar calendar;

    public PostService(PostRepository postRepository, PostDraftRepository postDraftRepository,
            PostQueryRepository postQueryRepository, PostAccess postAccess, BlogAccess blogAccess,
            CategoryAccess categoryAccess, TagQueryRepository tagQueryRepository,
            PostLikeRepository postLikeRepository, TrackbackQueryRepository trackbackQueryRepository,
            TrackbackUrls trackbackUrls, TrackbackSendService trackbackSendService, Clock clock,
            BlogCalendar calendar) {
        this.postRepository = postRepository;
        this.postDraftRepository = postDraftRepository;
        this.postQueryRepository = postQueryRepository;
        this.postAccess = postAccess;
        this.blogAccess = blogAccess;
        this.categoryAccess = categoryAccess;
        this.tagQueryRepository = tagQueryRepository;
        this.postLikeRepository = postLikeRepository;
        this.trackbackQueryRepository = trackbackQueryRepository;
        this.trackbackUrls = trackbackUrls;
        this.trackbackSendService = trackbackSendService;
        this.clock = clock;
        this.calendar = calendar;
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
        PostListFilter normalized = new PostListFilter(filter.categoryId(), tag == null || tag.isEmpty() ? null : tag,
                filter.month(), null, null);
        if (filter.month() != null) {
            BlogCalendar.Range range = calendar.monthRange(filter.month());
            normalized = normalized.withRange(range.from(), range.to());
        }
        return withTags(postQueryRepository.findListablePosts(blog.getId(), normalized, pageable));
    }

    /** 블로그 공지 목록(004 FR-059): 목록 노출 가능 공지, 발행 최신순. 쿼리 4회(블로그, 목록, 전체 수, 태그). */
    @Transactional(readOnly = true)
    public Page<PostSummaryResponse> notices(String handle, Pageable pageable) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        return withTags(postQueryRepository.findNotices(blog.getId(), pageable));
    }

    private Page<PostSummaryResponse> withTags(Page<PostSummaryRow> rows) {
        Map<Long, List<String>> tags = tagQueryRepository.findTagNames(
                rows.getContent().stream().map(PostSummaryRow::id).toList());
        return rows.map(row -> row.toPublicResponse(tags.get(row.id())));
    }

    /**
     * 글 상세. 주인 외에게는 목록 노출 가능 글만, 주인에게는 DRAFT·PRIVATE·SCHEDULED도 보인다(DELETED는 상세에서 404).
     * 보호 글(004)은 주인이 아니고 유효한 열람 쿠키가 없으면 잠긴 상세({@code locked: true})다.
     * 쿼리: 글(블로그·주인·카테고리 fetch join) 1회 + 태그 1회(잠긴 글은 없음) + 발행된 글이면 이전·다음 2회
     * + 로그인했으면 좋아요 여부 1회(002) + 발행된 적이 있는 열린 글이면 트랙백 수 1회(005).
     */
    @Transactional(readOnly = true)
    public PostDetailResponse detail(Long postId, Long viewerId, PostUnlockCheck unlock) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> PostExposure.isDetailVisibleTo(p, viewerId))
                .orElseThrow(() -> PostAccess.notFound(postId));
        return detailOf(post, viewerId, PostExposure.isLocked(post, viewerId, unlock.isUnlocked(post)));
    }

    /** 열람 쿠키가 없는 요청의 상세. */
    @Transactional(readOnly = true)
    public PostDetailResponse detail(Long postId, Long viewerId) {
        return detail(postId, viewerId, PostUnlockCheck.NONE);
    }

    /**
     * 이미 읽은 글(블로그·주인 포함)을 이 사람({@code viewerId}, 비로그인 null)에게 보여줄 상세 응답. {@code contentMarkdown}은 주인에게만,
     * {@code likedByMe}는 비로그인이면 null.
     */
    PostDetailResponse detailOf(Post post, Long viewerId) {
        return detailOf(post, viewerId, false);
    }

    PostDetailResponse detailOf(Post post, Long viewerId, boolean locked) {
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
        // 005: 트랙백 주소는 핑을 받는 글에만, 개수는 발행된 적이 있는 열린 글만 센다(쿼리 1회).
        String trackbackUrl = TrackbackVisibility.acceptsPings(post)
                ? trackbackUrls.trackbackUrl(post.getBlog().getHandle(), post.getId()) : null;
        long trackbackCount = locked || post.getPublishedAt() == null ? 0
                : trackbackQueryRepository.countVisible(post.getId());
        return PostDetailResponse.of(post, owner, prev, next, locked ? List.of() : tagNames(post.getId()), likedByMe,
                locked, trackbackUrl, trackbackCount);
    }

    /**
     * 예약 취소(004 research B5): SCHEDULED → DRAFT, 예약 시각을 지운다. 예약 상태가 아니면(이미 발행됨 등) 409
     * {@code POST_NOT_SCHEDULED}. 주인만(남의 글 403, 휴지통 글 404).
     */
    @Transactional
    public PostSummaryResponse unschedule(long userId, Long postId) {
        Post post = postAccess.requireOwnedEditablePost(postId, userId);
        if (!post.isScheduled()) {
            throw new BusinessException(ErrorCode.POST_NOT_SCHEDULED, "Post is not scheduled: " + postId);
        }
        post.unschedule();
        // 005 FR-052: 예약 발행 때 보내려던 트랙백 요청(PENDING)은 지운다.
        trackbackSendService.discardPending(List.of(postId));
        postRepository.flush();
        return summaryOf(post);
    }

    /** 휴지통으로(FR-084). 이미 휴지통이면 404(상세와 같다). 남의 글 403. */
    @Transactional
    public void delete(long userId, Long postId) {
        postAccess.requireOwnedEditablePost(postId, userId).moveToTrash(clock.instant());
        // 005 FR-052: 휴지통에 보낸 글의 보내지 않은 트랙백 요청(PENDING)은 지운다.
        trackbackSendService.discardPending(List.of(postId));
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
        return summaryOf(post);
    }

    /** 주인에게 돌려줄 한 줄(예약 시각 포함). */
    private PostSummaryResponse summaryOf(Post post) {
        Long postId = post.getId();
        return new PostSummaryResponse(post.getId(), post.getTitle(), post.getSummary(), post.getThumbnailUrl(),
                CategoryRef.of(post.getCategory()), tagNames(postId), post.getViewCount(), post.getCommentCount(),
                post.getVisibility(), post.getStatus(),
                post.getPublishedAt(), post.getUpdatedAt(), postDraftRepository.existsById(postId),
                post.isNotice(), post.getScheduledAt(), null, null);
    }

    /** 글 하나의 태그 이름(이름순). 쿼리 1회. */
    List<String> tagNames(Long postId) {
        return tagQueryRepository.findTagNames(List.of(postId)).getOrDefault(postId, List.of());
    }
}
