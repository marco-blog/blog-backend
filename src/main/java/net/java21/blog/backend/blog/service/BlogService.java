package net.java21.blog.backend.blog.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.BlogsProperties;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.domain.FeedContentMode;
import net.java21.blog.backend.blog.dto.BlogResponse;
import net.java21.blog.backend.blog.dto.CreateBlogRequest;
import net.java21.blog.backend.blog.dto.HandleAvailabilityResponse;
import net.java21.blog.backend.blog.dto.MyBlogsResponse;
import net.java21.blog.backend.blog.dto.UpdateBlogRequest;
import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.category.repository.CategoryQueryRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.service.MediaReferenceService;
import net.java21.blog.backend.subscription.repository.BlogSubscriptionRepository;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.spam.BannedWordMatcher;
import net.java21.blog.backend.topic.service.TopicService;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 만들기·조회·수정·삭제(FR-010~012, FR-158, FR-159).
 * 만들기와 삭제는 한 트랜잭션에서 회원 행을 {@code SELECT ... FOR UPDATE}로 잠근 뒤 ACTIVE 블로그 수를 센다(research R28).
 * 같은 회원의 동시 요청은 이 잠금에서 줄을 서므로 한도를 넘거나 마지막 블로그가 지워지지 않는다.
 */
@Service
public class BlogService {

    private final BlogRepository blogRepository;
    private final BlogQueryRepository blogQueryRepository;
    private final UserRepository userRepository;
    private final BlogAccess blogAccess;
    private final HandlePolicy handlePolicy;
    private final PasswordEncoder passwordEncoder;
    private final BlogsProperties blogsProperties;
    private final CategoryQueryRepository categoryQueryRepository;
    private final MediaReferenceService mediaReferences;
    private final BlogSubscriptionRepository subscriptionRepository;
    private final TopicService topicService;
    private final BannedWordMatcher bannedWords;
    private final Clock clock;

    public BlogService(BlogRepository blogRepository, BlogQueryRepository blogQueryRepository,
            UserRepository userRepository, BlogAccess blogAccess, HandlePolicy handlePolicy,
            PasswordEncoder passwordEncoder, BlogsProperties blogsProperties,
            CategoryQueryRepository categoryQueryRepository, MediaReferenceService mediaReferences,
            BlogSubscriptionRepository subscriptionRepository, TopicService topicService,
            BannedWordMatcher bannedWords, Clock clock) {
        this.blogRepository = blogRepository;
        this.blogQueryRepository = blogQueryRepository;
        this.userRepository = userRepository;
        this.blogAccess = blogAccess;
        this.handlePolicy = handlePolicy;
        this.passwordEncoder = passwordEncoder;
        this.blogsProperties = blogsProperties;
        this.categoryQueryRepository = categoryQueryRepository;
        this.mediaReferences = mediaReferences;
        this.subscriptionRepository = subscriptionRepository;
        this.topicService = topicService;
        this.bannedWords = bannedWords;
        this.clock = clock;
    }

    /** 주소를 쓸 수 있는지(가입·새 블로그 만들기 화면). 삭제된 블로그의 주소는 TAKEN. */
    @Transactional(readOnly = true)
    public HandleAvailabilityResponse handleAvailability(String handle) {
        HandlePolicy.Violation violation = handlePolicy.violation(handle);
        if (violation == HandlePolicy.Violation.RESERVED) {
            return HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.RESERVED);
        }
        if (violation == HandlePolicy.Violation.INVALID) {
            return HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.INVALID);
        }
        if (blogRepository.existsByHandle(handle)) {
            return HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.TAKEN);
        }
        return HandleAvailabilityResponse.ofAvailable();
    }

    /** 내 블로그(삭제 제외, 만든 순)와 블로그 수·한도. 쿼리 2회(회원, 블로그 목록 + 글 수). */
    @Transactional(readOnly = true)
    public MyBlogsResponse myBlogs(long userId) {
        User user = requireActiveUser(userRepository.findById(userId).orElse(null));
        List<MyBlogsResponse.Item> items = blogQueryRepository.findMyBlogs(userId).stream()
                .map(row -> new MyBlogsResponse.Item(row.handle(), row.title(),
                        Media.urlOf(row.coverMediaKey()), row.postCount(), row.createdAt()))
                .toList();
        return new MyBlogsResponse(items, items.size(), user.effectiveBlogLimit(blogsProperties.defaultMaxPerMember()));
    }

    @Transactional
    public BlogResponse create(long userId, CreateBlogRequest request) {
        handlePolicy.check(request.handle());
        List<FieldError> banned = new ArrayList<>();
        bannedWords.collectName(banned, "handle", request.handle());
        if (!isBlank(request.title())) {
            bannedWords.collectName(banned, "title", request.title());
        }
        if (!banned.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Banned word", banned);
        }
        User user = requireActiveUser(userRepository.findByIdForUpdate(userId).orElse(null));
        long activeBlogs = blogRepository.countByUserIdAndStatus(userId, BlogStatus.ACTIVE);
        if (activeBlogs >= user.effectiveBlogLimit(blogsProperties.defaultMaxPerMember())) {
            throw new BusinessException(ErrorCode.BLOG_LIMIT_EXCEEDED, "Blog limit reached");
        }
        if (blogRepository.existsByHandle(request.handle())) {
            throw new BusinessException(ErrorCode.HANDLE_TAKEN, "Handle taken");
        }
        String title = isBlank(request.title()) ? Blog.defaultTitle(user.getNickname()) : request.title().strip();
        Blog blog = new Blog(user, request.handle(), title);
        try {
            blogRepository.saveAndFlush(blog);
        } catch (DataIntegrityViolationException e) {
            // 다른 회원이 같은 주소를 먼저 저장했다: UNIQUE 제약이 최종 판단한다.
            throw new BusinessException(ErrorCode.HANDLE_TAKEN, "Handle taken");
        }
        return BlogResponse.of(blog);
    }

    /**
     * 블로그와 카테고리 트리(목록 노출 가능 글 수), 구독자 수·피드 설정, 요청한 회원({@code viewerId}, 비로그인 null)의 구독 여부.
     * 쿼리 3회(블로그, 카테고리, 글 수) + 로그인했으면 구독 여부 1회.
     */
    @Transactional(readOnly = true)
    public BlogResponse get(String handle, Long viewerId) {
        Blog blog = blogAccess.requireVisibleBlogForPage(handle);
        Boolean subscribedByMe = viewerId == null ? null
                : subscriptionRepository.existsByUserIdAndBlogId(viewerId, blog.getId());
        return BlogResponse.of(blog, categoryQueryRepository.findTree(blog.getId()), subscribedByMe);
    }

    @Transactional
    public BlogResponse update(long userId, String handle, UpdateBlogRequest request) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        Media cover = null;
        if (request.hasCoverImageMediaKey() && request.getCoverImageMediaKey() != null) {
            // 대표 이미지는 purpose=BLOG_COVER로 올린 본인 이미지만(FR-012, contracts/api.md)
            cover = mediaReferences.findOwned(userId, request.getCoverImageMediaKey(), MediaPurpose.BLOG_COVER);
            if (cover == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                        List.of(FieldError.of("coverImageMediaKey", "INVALID")));
            }
        }
        if (request.hasTitle()) {
            if (isBlank(request.getTitle())) {
                throw required("title");
            }
            bannedWords.requireCleanName("title", request.getTitle());
            blog.changeTitle(request.getTitle().strip());
        }
        if (request.hasDescription()) {
            blog.changeDescription(isBlank(request.getDescription()) ? null : request.getDescription());
        }
        if (request.hasCommentEnabled()) {
            if (request.getCommentEnabled() == null) {
                throw required("commentEnabled");
            }
            blog.changeCommentEnabled(request.getCommentEnabled());
        }
        if (request.hasCoverImageMediaKey()) {
            changeCover(blog, cover);
        }
        if (request.hasFeedItemCount() || request.hasFeedContentMode()) {
            changeFeedSettings(blog, request);
        }
        if (request.hasPortalEnabled() || request.hasDefaultTopicId()) {
            changePortalSettings(blog, request);
        }
        if (request.hasGuestbookEnabled() || request.hasGuestWriteEnabled()) {
            changeGuestSettings(blog, request);
        }
        if (request.hasTrackbackEnabled()) {
            if (request.getTrackbackEnabled() == null) {
                throw required("trackbackEnabled");
            }
            blog.changeTrackbackEnabled(request.getTrackbackEnabled());
        }
        return BlogResponse.of(blog, categoryQueryRepository.findTree(blog.getId()), false);
    }

    /** 방명록·비회원 쓰기 설정(004 FR-058, FR-066, research B15): 보낸 값만 바꾸며 지울 수 없다(null이면 400 {@code REQUIRED}). */
    private static void changeGuestSettings(Blog blog, UpdateBlogRequest request) {
        boolean guestbook = blog.isGuestbookEnabled();
        boolean guestWrite = blog.isGuestWriteEnabled();
        if (request.hasGuestbookEnabled()) {
            if (request.getGuestbookEnabled() == null) {
                throw required("guestbookEnabled");
            }
            guestbook = request.getGuestbookEnabled();
        }
        if (request.hasGuestWriteEnabled()) {
            if (request.getGuestWriteEnabled() == null) {
                throw required("guestWriteEnabled");
            }
            guestWrite = request.getGuestWriteEnabled();
        }
        blog.changeGuestSettings(guestbook, guestWrite);
    }

    /**
     * 포털 설정(003 FR-077·089, contracts/api.md): {@code portalEnabled}는 지울 수 없고, {@code defaultTopicId}는 null이면 지우며
     * 값이면 소분류·운영자 숨김 아님이어야 한다(지금 값과 같으면 검사하지 않음).
     */
    private void changePortalSettings(Blog blog, UpdateBlogRequest request) {
        boolean portalEnabled = blog.isPortalEnabled();
        if (request.hasPortalEnabled()) {
            if (request.getPortalEnabled() == null) {
                throw required("portalEnabled");
            }
            portalEnabled = request.getPortalEnabled();
        }
        Topic defaultTopic = blog.getDefaultTopic();
        if (request.hasDefaultTopicId()) {
            defaultTopic = topicService.requireSelectable(request.getDefaultTopicId(), blog.getDefaultTopicId());
        }
        blog.changePortalSettings(portalEnabled, defaultTopic);
    }

    /** 피드 설정(002 FR-046, contracts/api.md): 보낸 값만 바꾸며 지울 수 없고, 허용 값이 아니면 {@code INVALID}(params.allowed). */
    private static void changeFeedSettings(Blog blog, UpdateBlogRequest request) {
        int count = blog.getFeedItemCount();
        if (request.hasFeedItemCount()) {
            if (request.getFeedItemCount() == null) {
                throw required("feedItemCount");
            }
            if (!Blog.FEED_ITEM_COUNTS.contains(request.getFeedItemCount())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(new FieldError(
                        "feedItemCount", "INVALID", Map.of("allowed", Blog.FEED_ITEM_COUNTS.stream().sorted().toList()))));
            }
            count = request.getFeedItemCount();
        }
        FeedContentMode mode = blog.getFeedContentMode();
        if (request.hasFeedContentMode()) {
            if (request.getFeedContentMode() == null) {
                throw required("feedContentMode");
            }
            mode = Arrays.stream(FeedContentMode.values())
                    .filter(m -> m.name().equals(request.getFeedContentMode()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                            List.of(new FieldError("feedContentMode", "INVALID", Map.of("allowed",
                                    Arrays.stream(FeedContentMode.values()).map(Enum::name).toList())))));
        }
        blog.changeFeedSettings(count, mode);
    }

    /**
     * 블로그 삭제(FR-159): 비밀번호 확인 → 회원 행 잠금 → ACTIVE 블로그가 2개 이상일 때만 DELETED,
     * 그 블로그의 휴지통에 없는 글은 모두 휴지통으로. 되돌릴 수 없고 주소는 다시 쓸 수 없다.
     */
    @Transactional
    public void delete(long userId, String handle, String password) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        User user = requireActiveUser(userRepository.findByIdForUpdate(userId).orElse(null));
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH, "Password mismatch");
        }
        if (blogRepository.countByUserIdAndStatus(userId, BlogStatus.ACTIVE) < 2) {
            throw new BusinessException(ErrorCode.LAST_BLOG_CANNOT_BE_DELETED, "Cannot delete the last blog");
        }
        Instant now = clock.instant();
        blog.delete(now);
        blogRepository.flush();
        blogQueryRepository.moveAllPostsToTrash(blog.getId(), now);
    }

    /** 대표 이미지를 바꾸고(ATTACHED로) 이전 이미지는 정리 대상 판단(FR-073). */
    private void changeCover(Blog blog, Media cover) {
        Long previousId = blog.getCoverMediaId();
        if (cover != null) {
            if (cover.getId().equals(previousId)) {
                return;
            }
            mediaReferences.attach(cover);
        }
        blog.changeCoverMedia(cover);
        if (previousId != null) {
            mediaReferences.reevaluate(List.of(previousId));
        }
    }

    private static User requireActiveUser(User user) {
        if (user == null || !user.isActive()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Member is not active");
        }
        return user;
    }

    private static BusinessException required(String field) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                List.of(FieldError.of(field, "REQUIRED")));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
