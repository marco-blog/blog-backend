package net.java21.blog.backend.external.member;

import java.time.Clock;
import java.time.Instant;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.MyExternalBlogResponse;
import net.java21.blog.backend.external.dto.MyExternalPostResponse;
import net.java21.blog.backend.external.repository.ClassificationReviewRepository;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.service.TopicService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증된 주인의 주제 고치기(007 FR-120, US3 AS1). 관리 회원이 아니면 존재를 숨겨 404, 소유 인증이 없으면 403
 * {@code EXTERNAL_BLOG_OWNERSHIP_REQUIRED}, 해제된 등록이면 409. 글 주제는 출처 {@code OWNER}로 바꾸고 사람이 정한 시각을 남기며
 * 그 글의 대기 검수는 {@code SKIPPED}로 닫는다. 사람이 정한 주제는 이후 수집에서도 바뀌지 않는다(수집은 새 글만 분류).
 */
@Service
public class ExternalPostTopicService {

    private final ExternalBlogRepository blogRepository;
    private final ExternalPostRepository postRepository;
    private final ClassificationReviewRepository reviewRepository;
    private final TopicService topicService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ExternalPostTopicService(ExternalBlogRepository blogRepository, ExternalPostRepository postRepository,
            ClassificationReviewRepository reviewRepository, TopicService topicService,
            ApplicationEventPublisher events, Clock clock) {
        this.blogRepository = blogRepository;
        this.postRepository = postRepository;
        this.reviewRepository = reviewRepository;
        this.topicService = topicService;
        this.events = events;
        this.clock = clock;
    }

    /** 기본 주제: 이후 수집되는 글부터(이미 수집된 DEFAULT 글은 그대로). */
    @Transactional
    public MyExternalBlogResponse changeDefaultTopic(long userId, long blogId, Long topicId) {
        ExternalBlog blog = requireOwner(userId, blogId, "default-topic");
        if (topicId == null) {
            throw MemberExternalBlogService.invalid(FieldError.of("defaultTopicId", "REQUIRED"));
        }
        Topic topic = topicService.requireSelectable(topicId, blog.getDefaultTopic().getId());
        blog.changeDefaultTopic(topic);
        blogRepository.flush();
        return MyExternalBlogResponse.of(blog,
                postRepository.countByExternalBlogIdAndStatus(blogId, ExternalPostStatus.ACTIVE));
    }

    /** 글 주제(출처 OWNER). 내린 글·다른 등록의 글은 404 {@code EXTERNAL_POST_NOT_FOUND}. */
    @Transactional
    public MyExternalPostResponse changePostTopic(long userId, long blogId, long postId, Long topicId) {
        ExternalBlog blog = requireOwner(userId, blogId, "post-topic");
        ExternalPost post = postRepository.findById(postId)
                .filter(p -> p.getExternalBlog().getId().equals(blogId) && p.getStatus() == ExternalPostStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXTERNAL_POST_NOT_FOUND,
                        "External post not found: " + postId));
        if (topicId == null) {
            throw MemberExternalBlogService.invalid(FieldError.of("topicId", "REQUIRED"));
        }
        Topic topic = topicService.requireSelectable(topicId, post.getTopic().getId());
        Instant now = clock.instant();
        post.changeTopic(topic, TopicSource.OWNER, now);
        reviewRepository.findByExternalPostId(postId).ifPresent(review -> review.skip());
        postRepository.flush();
        events.publishEvent(new PortalChangedEvent("external:owner-topic"));
        return MyExternalPostResponse.of(post, blog.isOwnershipVerified());
    }

    private ExternalBlog requireOwner(long userId, long blogId, String action) {
        ExternalBlog blog = blogRepository.findById(blogId).filter(b -> b.isManagedBy(userId))
                .orElseThrow(() -> MemberExternalBlogService.notFound(blogId));
        if (!blog.isOwnershipVerified()) {
            throw new BusinessException(ErrorCode.EXTERNAL_BLOG_OWNERSHIP_REQUIRED, "Ownership required");
        }
        blog.requireNotReleased(action);
        return blog;
    }
}
