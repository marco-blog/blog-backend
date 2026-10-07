package net.java21.blog.backend.external.member;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalBlogVerification;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.dto.MyExternalBlogResponse;
import net.java21.blog.backend.external.dto.MyExternalPostResponse;
import net.java21.blog.backend.external.feed.FeedDiscovery;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.repository.ExternalBlogQueryRepository;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalBlogVerificationRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.external.thumbnail.ThumbnailBackfillRequested;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.service.TopicService;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원의 외부 블로그 신청·넘겨받기·조회(007 FR-111, FR-112, FR-129, research E6·E8). 피드 확인(외부 요청)은 트랜잭션 밖에서 먼저 하고,
 * 저장은 회원 행 {@code FOR UPDATE}로 한도(3개)를 지킨다. 같은 피드의 활성 등록이 동시에 생기면 MySQL
 * {@code uk_external_blogs_active_feed_hash}가 막고 같은 409로 바꾼다.
 */
@Service
public class MemberExternalBlogService {

    private final ExternalBlogRepository blogRepository;
    private final ExternalBlogQueryRepository queryRepository;
    private final ExternalBlogVerificationRepository verificationRepository;
    private final ExternalPostRepository postRepository;
    private final UserRepository userRepository;
    private final TopicService topicService;
    private final FeedDiscovery discovery;
    private final ExternalFeedProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MemberExternalBlogService(ExternalBlogRepository blogRepository, ExternalBlogQueryRepository queryRepository,
            ExternalBlogVerificationRepository verificationRepository, ExternalPostRepository postRepository,
            UserRepository userRepository, TopicService topicService, FeedDiscovery discovery,
            ExternalFeedProperties properties, ApplicationEventPublisher events, TransactionTemplate tx, Clock clock) {
        this.blogRepository = blogRepository;
        this.queryRepository = queryRepository;
        this.verificationRepository = verificationRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.topicService = topicService;
        this.discovery = discovery;
        this.properties = properties;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MyExternalBlogResponse> list(long userId) {
        return queryRepository.findMemberBlogs(userId).stream()
                .map(row -> MyExternalBlogResponse.of(row.blog(), row.postCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public MyExternalBlogResponse get(long userId, long id) {
        ExternalBlog blog = requireManaged(userId, id);
        return MyExternalBlogResponse.of(blog, activeCount(blog));
    }

    @Transactional(readOnly = true)
    public Page<MyExternalPostResponse> posts(long userId, long id, Pageable pageable) {
        ExternalBlog blog = requireManaged(userId, id);
        return postRepository.findPage(blog.getId(), null, pageable)
                .map(p -> MyExternalPostResponse.of(p, blog.isOwnershipVerified()));
    }

    /** 신청(PENDING). 결과는 저장한 등록. */
    public MyExternalBlogResponse create(long userId, String feedUrl, Long defaultTopicId, Long verificationId) {
        URI input = discovery.requireAllowed("feedUrl", feedUrl);
        if (defaultTopicId == null) {
            throw invalid(FieldError.of("defaultTopicId", "REQUIRED"));
        }
        topicService.requireSelectable(defaultTopicId, null);
        FeedDiscovery.Discovered feed = discovery.readFeed(input);
        String finalUrl = feed.feedUri().toString();
        String hash = FeedUrlNormalizer.hash(finalUrl);
        try {
            return tx.execute(status -> {
                Instant now = clock.instant();
                User user = userRepository.findByIdForUpdate(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "No user"));
                requireUnderLimit(userId);
                requireFree(hash, userId);
                ExternalBlogVerification verification = verificationId == null ? null
                        : requireProof(userId, verificationId, hash, now);
                Topic topic = topicService.requireSelectable(defaultTopicId, null);
                ExternalBlog blog = ExternalBlog.memberRequest(user, finalUrl, hash, topic);
                blog.describe(feed.feed().title(), feed.feed().siteUrl(), feed.feed().format());
                if (verification != null) {
                    blog.markVerified(now);
                }
                blogRepository.saveAndFlush(blog);
                if (verification != null) {
                    verification.link(blog);
                }
                return MyExternalBlogResponse.of(blog, 0);
            });
        } catch (DataIntegrityViolationException e) {
            throw alreadyRegistered(blogRepository.findHolding(hash).orElse(null), userId);
        }
    }

    /** 소유 인증으로 넘겨받기(FR-129). */
    public MyExternalBlogResponse claim(long userId, long id, Long verificationId) {
        if (verificationId == null) {
            throw invalid(FieldError.of("verificationId", "REQUIRED"));
        }
        MyExternalBlogResponse result = tx.execute(status -> {
            Instant now = clock.instant();
            User user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "No user"));
            ExternalBlog blog = blogRepository.findById(id)
                    .filter(b -> b.getStatus() != ExternalBlogStatus.REJECTED
                            && b.getStatus() != ExternalBlogStatus.RELEASED)
                    .orElseThrow(() -> notFound(id));
            if (blog.getStatus() == ExternalBlogStatus.BLOCKED) {
                throw blog.conflict("claim", Map.of());
            }
            ExternalBlogVerification verification = requireProof(userId, verificationId, blog.getFeedUrlHash(), null);
            if (blog.isManagedBy(userId) && blog.isOwnershipVerified()) {
                return MyExternalBlogResponse.of(blog, activeCount(blog));
            }
            if (!blog.isManagedBy(userId)) {
                requireUnderLimit(userId);
            }
            blog.claim(user, now);
            verification.link(blog);
            blogRepository.flush();
            events.publishEvent(new ThumbnailBackfillRequested(blog.getId()));
            return MyExternalBlogResponse.of(blog, activeCount(blog));
        });
        return result;
    }

    /** 관리 회원의 등록. 아니면 존재를 숨겨 404. */
    ExternalBlog requireManaged(long userId, long id) {
        return blogRepository.findById(id).filter(b -> b.isManagedBy(userId)).orElseThrow(() -> notFound(id));
    }

    private long activeCount(ExternalBlog blog) {
        return postRepository.countByExternalBlogIdAndStatus(blog.getId(), ExternalPostStatus.ACTIVE);
    }

    private void requireUnderLimit(long userId) {
        int max = properties.memberLimit();
        if (blogRepository.countCounted(userId) >= max) {
            throw BusinessException.withParams(ErrorCode.EXTERNAL_BLOG_LIMIT_EXCEEDED, "External blog limit",
                    Map.of("max", max));
        }
    }

    private void requireFree(String hash, long userId) {
        ExternalBlog holding = blogRepository.findHolding(hash).orElse(null);
        if (holding != null) {
            throw alreadyRegistered(holding, userId);
        }
    }

    /**
     * 이 회원의 성공한 인증이고 피드 해시가 같아야 한다(신청은 24시간 안 — {@code now}가 있을 때). 아니면 400 field
     * {@code verificationId} {@code INVALID}.
     */
    private ExternalBlogVerification requireProof(long userId, long verificationId, String hash, Instant now) {
        ExternalBlogVerification v = verificationRepository.findById(verificationId).orElse(null);
        boolean ok = v != null && v.provesOwnership(userId, hash)
                && (now == null || v.getVerifiedAt().isAfter(now.minus(properties.verificationTtl())));
        if (!ok) {
            throw invalid(FieldError.of("verificationId", "INVALID"));
        }
        return v;
    }

    /** 409 {@code EXTERNAL_BLOG_ALREADY_REGISTERED}({@code externalBlogId}, {@code status}, {@code claimable}). */
    static BusinessException alreadyRegistered(ExternalBlog holding, long userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (holding != null) {
            params.put("externalBlogId", holding.getId());
            params.put("status", holding.getStatus().name());
            params.put("claimable", holding.getStatus() != ExternalBlogStatus.BLOCKED);
            params.put("mine", holding.isManagedBy(userId));
        }
        return BusinessException.withParams(ErrorCode.EXTERNAL_BLOG_ALREADY_REGISTERED, "Feed already registered",
                params);
    }

    static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.EXTERNAL_BLOG_NOT_FOUND, "External blog not found: " + id);
    }

    static BusinessException invalid(FieldError error) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(error));
    }
}
