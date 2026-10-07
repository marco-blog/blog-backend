package net.java21.blog.backend.admin.external;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.dto.AdminExternalBlogResponse;
import net.java21.blog.backend.external.dto.AdminExternalPostResponse;
import net.java21.blog.backend.external.feed.FeedDiscovery;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.repository.ExternalBlogQueryRepository;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.notification.service.ExternalBlogNotifier;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.service.TopicService;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 외부 블로그 관리(007 FR-111, FR-127, contracts/api.md "관리자 API"). 상태 전이·알림·작업 기록은 같은 트랜잭션이다. 승인과
 * 직접 등록 때는 같은 피드의 해제된 등록에 남은 글(탈퇴로 내린 글 제외)을 새 등록으로 옮긴다(결정 표 24번, research E16).
 */
@Service
public class AdminExternalBlogService {

    static final int REASON_MAX = ExternalBlog.REASON_MAX;
    static final int QUERY_MIN = 2;
    static final int QUERY_MAX = 100;

    private final ExternalBlogRepository blogRepository;
    private final ExternalBlogQueryRepository queryRepository;
    private final ExternalPostRepository postRepository;
    private final PortalExclusionRepository exclusionRepository;
    private final UserRepository userRepository;
    private final TopicService topicService;
    private final FeedDiscovery discovery;
    private final AdminAuditService auditService;
    private final ExternalBlogNotifier notifier;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AdminExternalBlogService(ExternalBlogRepository blogRepository, ExternalBlogQueryRepository queryRepository,
            ExternalPostRepository postRepository, PortalExclusionRepository exclusionRepository,
            UserRepository userRepository, TopicService topicService, FeedDiscovery discovery,
            AdminAuditService auditService, ExternalBlogNotifier notifier, ApplicationEventPublisher events,
            TransactionTemplate tx, Clock clock) {
        this.blogRepository = blogRepository;
        this.queryRepository = queryRepository;
        this.postRepository = postRepository;
        this.exclusionRepository = exclusionRepository;
        this.userRepository = userRepository;
        this.topicService = topicService;
        this.discovery = discovery;
        this.auditService = auditService;
        this.notifier = notifier;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AdminExternalBlogResponse> list(ExternalBlogStatus status, String q, Pageable pageable) {
        String query = q == null || q.isBlank() ? null : q.strip();
        if (query != null && (query.length() < QUERY_MIN || query.length() > QUERY_MAX)) {
            throw invalid(new FieldError("q", query.length() < QUERY_MIN ? "TOO_SHORT" : "TOO_LONG",
                    Map.of(query.length() < QUERY_MIN ? "min" : "max",
                            query.length() < QUERY_MIN ? QUERY_MIN : QUERY_MAX)));
        }
        return queryRepository.findAdminBlogs(status, query, pageable)
                .map(row -> AdminExternalBlogResponse.of(row.blog(), row.postCount(), row.pendingReviewCount()));
    }

    @Transactional(readOnly = true)
    public AdminExternalBlogResponse get(long id) {
        ExternalBlogQueryRepository.BlogRow row = queryRepository.findRow(id);
        if (row == null) {
            throw notFound(id);
        }
        return AdminExternalBlogResponse.of(row.blog(), row.postCount(), row.pendingReviewCount());
    }

    @Transactional(readOnly = true)
    public Page<AdminExternalPostResponse> posts(long id, ExternalPostStatus status, Pageable pageable) {
        ExternalBlog blog = blogRepository.findById(id).orElseThrow(() -> notFound(id));
        Page<ExternalPost> page = postRepository.findPage(id, status, pageable);
        List<Long> ids = page.getContent().stream().map(ExternalPost::getId).toList();
        Map<Long, PortalExclusion> exclusions = ids.isEmpty() ? Map.of()
                : exclusionRepository.findByExternalPostIds(ids).stream()
                        .collect(Collectors.toMap(e -> e.getExternalPost().getId(), Function.identity()));
        return page.map(p -> AdminExternalPostResponse.of(p, blog.isOwnershipVerified(), exclusions.get(p.getId())));
    }

    /** 직접 등록(바로 ACTIVE, 회원 없음, 작업 기록 {@code EXTERNAL_BLOG_CREATE}). */
    public AdminExternalBlogResponse create(long adminId, String feedUrl, Long defaultTopicId, String basis,
            String requestIp) {
        URI input = discovery.requireAllowed("feedUrl", feedUrl);
        String text = requireText("registrationBasis", basis);
        if (defaultTopicId == null) {
            throw invalid(FieldError.of("defaultTopicId", "REQUIRED"));
        }
        topicService.requireSelectable(defaultTopicId, null);
        FeedDiscovery.Discovered feed = discovery.readFeed(input);
        String finalUrl = feed.feedUri().toString();
        String hash = FeedUrlNormalizer.hash(finalUrl);
        long id;
        try {
            id = tx.execute(status -> {
                Instant now = clock.instant();
                ExternalBlog holding = blogRepository.findHolding(hash).orElse(null);
                if (holding != null) {
                    throw alreadyRegistered(holding);
                }
                Topic topic = topicService.requireSelectable(defaultTopicId, null);
                ExternalBlog blog = ExternalBlog.adminDirect(userRepository.getReferenceById(adminId), text, finalUrl,
                        hash, topic, now);
                blog.describe(feed.feed().title(), feed.feed().siteUrl(), feed.feed().format());
                blogRepository.saveAndFlush(blog);
                takeOverKeptPosts(blog, now);
                Map<String, Object> after = new HashMap<>();
                after.put("feedUrl", finalUrl);
                after.put("defaultTopicId", defaultTopicId);
                after.put("status", blog.getStatus().name());
                auditService.record(adminId, AuditActions.EXTERNAL_BLOG_CREATE, AuditActions.TARGET_EXTERNAL_BLOG,
                        blog.getId(), null, null, after, text, requestIp);
                return blog.getId();
            });
        } catch (DataIntegrityViolationException e) {
            throw alreadyRegistered(blogRepository.findHolding(hash).orElse(null));
        }
        return get(id);
    }

    /** 기본 주제 변경(이후 수집되는 글부터, 작업 기록 전후). */
    @Transactional
    public AdminExternalBlogResponse updateDefaultTopic(long adminId, long id, Long topicId, String requestIp) {
        if (topicId == null) {
            throw invalid(FieldError.of("defaultTopicId", "REQUIRED"));
        }
        ExternalBlog blog = blogRepository.findById(id).orElseThrow(() -> notFound(id));
        Long before = blog.getDefaultTopic().getId();
        Topic topic = topicService.requireSelectable(topicId, before);
        blog.changeDefaultTopic(topic);
        blogRepository.flush();
        auditService.record(adminId, AuditActions.EXTERNAL_BLOG_UPDATE, AuditActions.TARGET_EXTERNAL_BLOG, id,
                value("defaultTopicId", before), value("defaultTopicId", topicId), requestIp);
        return get(id);
    }

    /** 승인(PENDING만): ACTIVE·바로 수집, 남은 글 이어받기, 신청 회원 알림. */
    @Transactional
    public AdminExternalBlogResponse approve(long adminId, long id, String requestIp) {
        Instant now = clock.instant();
        ExternalBlog blog = blogRepository.findById(id).orElseThrow(() -> notFound(id));
        String before = blog.getStatus().name();
        blog.approve(userRepository.getReferenceById(adminId), now);
        blogRepository.flush();
        takeOverKeptPosts(blog, now);
        if (blog.getMember() != null) {
            notifier.notifyExternalBlog(blog.getMember().getId(), NotificationType.EXTERNAL_BLOG_APPROVED, id,
                    Map.of("externalBlogTitle", blog.displayTitle()));
        }
        auditService.record(adminId, AuditActions.EXTERNAL_BLOG_APPROVE, AuditActions.TARGET_EXTERNAL_BLOG, id,
                value("status", before), value("status", blog.getStatus().name()), requestIp);
        return get(id);
    }

    /** 거절(PENDING만, 사유 필수): 신청 회원 알림 {@code { externalBlogTitle, reason }}. */
    @Transactional
    public AdminExternalBlogResponse reject(long adminId, long id, String reason, String requestIp) {
        String text = requireText("reason", reason);
        ExternalBlog blog = blogRepository.findById(id).orElseThrow(() -> notFound(id));
        String before = blog.getStatus().name();
        blog.reject(userRepository.getReferenceById(adminId), text, clock.instant());
        blogRepository.flush();
        if (blog.getMember() != null) {
            notifier.notifyExternalBlog(blog.getMember().getId(), NotificationType.EXTERNAL_BLOG_REJECTED, id,
                    Map.of("externalBlogTitle", blog.displayTitle(), "reason", text));
        }
        auditService.record(adminId, AuditActions.EXTERNAL_BLOG_REJECT, AuditActions.TARGET_EXTERNAL_BLOG, id, null,
                value("status", before), value("status", blog.getStatus().name()), text, requestIp);
        return get(id);
    }

    /** 같은 피드의 해제된 등록에 남은 글을 {@code blog}로 옮긴다. 옮긴 글이 있으면 포털 캐시 무효화. */
    private void takeOverKeptPosts(ExternalBlog blog, Instant now) {
        int moved = postRepository.moveKeptPosts(blog.getFeedUrlHash(), blog.getId(), now);
        if (moved > 0) {
            events.publishEvent(new PortalChangedEvent("external:take-over"));
        }
    }

    static Map<String, Object> value(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    static String requireText(String field, String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw invalid(FieldError.of(field, "REQUIRED"));
        }
        if (text.length() > REASON_MAX) {
            throw invalid(new FieldError(field, "TOO_LONG", Map.of("max", REASON_MAX)));
        }
        return text;
    }

    static BusinessException alreadyRegistered(ExternalBlog holding) {
        Map<String, Object> params = new HashMap<>();
        if (holding != null) {
            params.put("externalBlogId", holding.getId());
            params.put("status", holding.getStatus().name());
            params.put("claimable", holding.getStatus() != ExternalBlogStatus.BLOCKED);
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
