package net.java21.blog.backend.external.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.MyExternalBlogResponse;
import net.java21.blog.backend.external.dto.MyExternalPostResponse;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.fetch.ExternalPostUpserter;
import net.java21.blog.backend.external.fetch.TopicAssigner;
import net.java21.blog.backend.external.repository.ClassificationReviewRepository;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.topic.service.TopicService;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/** 007 T060: 인증된 주인의 주제 고치기(US3 AS1, FR-120). 저장소는 H2. */
@JpaRepositoryTest
class ExternalPostTopicServiceTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private ClassificationReviewRepository reviewRepository;
    @Autowired
    private TopicRepository topicRepository;

    private MutableClock clock;
    private ApplicationEventPublisher events;
    private ExternalPostTopicService service;
    private ExternalFixtures x;
    private JpaFixtures f;
    private User owner;
    private User stranger;
    private Topic major;
    private Topic it;
    private Topic science;
    private ExternalBlog blog;
    private ExternalPost post;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(JpaFixtures.T0);
        f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        owner = f.user("owner");
        stranger = f.user("stranger");
        major = f.topic(null, "knowledge", 1);
        it = f.topic(major, "it-internet", 1);
        science = f.topic(major, "science", 2);
        events = mock(ApplicationEventPublisher.class);
        TopicService topics = new TopicService(topicRepository, null, null, null, null, null,
                PortalProperties.defaults());
        service = new ExternalPostTopicService(blogRepository, postRepository, reviewRepository, topics, events,
                clock);
        blog = x.blog(owner, it, ExternalBlogStatus.ACTIVE);
        blog.markVerified(JpaFixtures.T0);
        post = x.post(blog, "Post", it, JpaFixtures.T0);
        em.flush();
    }

    private ExternalPost reload(ExternalPost p) {
        em.flush();
        em.clear();
        return postRepository.findById(p.getId()).orElseThrow();
    }

    @Test
    void ownerChangesPostTopicAndSkipsPendingReview() {
        ClassificationReview review = x.review(post, science, 0.4);
        clock.advance(Duration.ofHours(1));

        MyExternalPostResponse changed = service.changePostTopic(owner.getId(), blog.getId(), post.getId(),
                science.getId());

        assertThat(changed.topicId()).isEqualTo(science.getId());
        assertThat(changed.topicSource()).isEqualTo(TopicSource.OWNER);
        ExternalPost saved = reload(post);
        assertThat(saved.getTopicSource()).isEqualTo(TopicSource.OWNER);
        assertThat(saved.getTopicDecidedAt()).isEqualTo(JpaFixtures.T0.plus(Duration.ofHours(1)));
        assertThat(reviewRepository.findById(review.getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.SKIPPED);
        verify(events).publishEvent(new PortalChangedEvent("external:owner-topic"));
    }

    @Test
    void ownerCanChangeAfterReviewWasConfirmed() {
        ClassificationReview review = x.review(post, science, 0.4);
        review.confirm(science, f.user("admin"), JpaFixtures.T0);
        post.changeTopic(science, TopicSource.REVIEW, JpaFixtures.T0);
        em.flush();

        service.changePostTopic(owner.getId(), blog.getId(), post.getId(), it.getId());

        assertThat(reload(post).getTopicSource()).isEqualTo(TopicSource.OWNER);
        assertThat(reviewRepository.findById(review.getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.CONFIRMED);
    }

    @Test
    void humanTopicSurvivesFeedRefresh() {
        service.changePostTopic(owner.getId(), blog.getId(), post.getId(), science.getId());
        em.flush();
        ExternalPostUpserter upserter = new ExternalPostUpserter(postRepository, em);
        TopicAssigner.Batch assigner = (item, defaultTopicId) -> TopicAssigner.Decision.defaultTopic(defaultTopicId);

        upserter.upsert(blog.getId(), it.getId(), List.of(new FeedItem(post.getGuid(), post.getLink(), "Edited",
                "new summary", null, JpaFixtures.T0, List.of())), assigner, JpaFixtures.T0.plusSeconds(60));

        ExternalPost saved = reload(post);
        assertThat(saved.getTitle()).isEqualTo("Edited");
        assertThat(saved.getTopic().getId()).isEqualTo(science.getId());
        assertThat(saved.getTopicSource()).isEqualTo(TopicSource.OWNER);
    }

    @Test
    void unverifiedManagerGetsForbiddenAndStrangerNotFound() {
        ExternalBlog unverified = x.blog(owner, it, ExternalBlogStatus.ACTIVE);
        ExternalPost other = x.post(unverified, "Other", it, JpaFixtures.T0);
        em.flush();

        assertThatThrownBy(() -> service.changePostTopic(owner.getId(), unverified.getId(), other.getId(),
                science.getId()))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EXTERNAL_BLOG_OWNERSHIP_REQUIRED);
        assertThatThrownBy(() -> service.changeDefaultTopic(owner.getId(), unverified.getId(), science.getId()))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EXTERNAL_BLOG_OWNERSHIP_REQUIRED);
        assertThatThrownBy(() -> service.changePostTopic(stranger.getId(), blog.getId(), post.getId(),
                science.getId()))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
        verify(events, never()).publishEvent(new PortalChangedEvent("external:owner-topic"));
    }

    @Test
    void removedOrForeignPostIsNotFound() {
        ExternalPost removed = x.removed(blog, "Gone", it, RemovedReason.ADMIN);
        ExternalBlog otherBlog = x.blog(owner, it, ExternalBlogStatus.ACTIVE);
        otherBlog.markVerified(JpaFixtures.T0);
        ExternalPost foreign = x.post(otherBlog, "Foreign", it, JpaFixtures.T0);
        em.flush();

        for (ExternalPost target : List.of(removed, foreign)) {
            assertThatThrownBy(() -> service.changePostTopic(owner.getId(), blog.getId(), target.getId(),
                    science.getId()))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.EXTERNAL_POST_NOT_FOUND);
        }
    }

    @Test
    void topicRulesAndRequiredValue() {
        assertThatThrownBy(() -> service.changePostTopic(owner.getId(), blog.getId(), post.getId(), major.getId()))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TOPIC_NOT_SELECTABLE);
        assertThatThrownBy(() -> service.changePostTopic(owner.getId(), blog.getId(), post.getId(), 999_999L))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TOPIC_NOT_FOUND);
        assertThatThrownBy(() -> service.changePostTopic(owner.getId(), blog.getId(), post.getId(), null))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.changeDefaultTopic(owner.getId(), blog.getId(), null))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void defaultTopicChangeKeepsCollectedDefaultPosts() {
        MyExternalBlogResponse changed = service.changeDefaultTopic(owner.getId(), blog.getId(), science.getId());

        assertThat(changed.defaultTopicId()).isEqualTo(science.getId());
        assertThat(changed.postCount()).isEqualTo(1);
        ExternalPost saved = reload(post);
        assertThat(saved.getTopic().getId()).isEqualTo(it.getId());
        assertThat(saved.getTopicSource()).isEqualTo(TopicSource.DEFAULT);
    }

    @Test
    void releasedRegistrationConflicts() {
        ExternalFixtures.moveTo(blog, ExternalBlogStatus.RELEASED);
        em.flush();

        assertThatThrownBy(() -> service.changeDefaultTopic(owner.getId(), blog.getId(), science.getId()))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT);
        assertThatThrownBy(() -> service.changePostTopic(owner.getId(), blog.getId(), post.getId(),
                science.getId()))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT);
    }
}
