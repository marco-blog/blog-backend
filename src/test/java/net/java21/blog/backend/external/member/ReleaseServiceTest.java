package net.java21.blog.backend.external.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostDailyClick;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.dto.MyExternalBlogResponse;
import net.java21.blog.backend.external.portal.ExternalPortalQueryRepository;
import net.java21.blog.backend.external.release.ExternalPostPurger;
import net.java21.blog.backend.external.repository.ExternalBlogQueryRepository;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.external.thumbnail.ThumbnailDeleteRequested;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

/**
 * 007 T072: 등록 해제(US4 AS1, FR-126, SC-020, research E16, 결정 표 24번). 남기기는 글이 그대로 포털 노출 조건을 만족하고, 삭제는
 * 같은 트랜잭션에서 포털 제외 → 글(검수·일별 클릭 CASCADE) 순으로 지우고 썸네일 파일은 커밋 뒤 이벤트로. H2.
 */
@JpaRepositoryTest
@Import({ExternalPortalQueryRepository.class, ExternalBlogQueryRepository.class})
class ReleaseServiceTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private ExternalBlogQueryRepository blogQueries;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private PortalExclusionRepository exclusionRepository;
    @Autowired
    private ExternalPortalQueryRepository portalQueries;

    private ApplicationEventPublisher events;
    private ReleaseService service;
    private ExternalFixtures x;
    private JpaFixtures f;
    private User owner;
    private User admin;
    private Topic topic;

    @BeforeEach
    void setUp() {
        f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        owner = f.user("owner");
        admin = f.user("admin");
        topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
        events = mock(ApplicationEventPublisher.class);
        service = new ReleaseService(blogRepository, postRepository,
                new ExternalPostPurger(postRepository, exclusionRepository, events), events);
    }

    private boolean visible(ExternalPost post) {
        return portalQueries.findVisibleLink(post.getId(), JpaFixtures.T0.plusSeconds(60)).isPresent();
    }

    private long count(String entity) {
        return em.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult();
    }

    @Test
    void keepLeavesPostsVisibleAndStopsFetching() {
        ExternalBlog blog = x.blog(owner, topic, ExternalBlogStatus.ACTIVE);
        ExternalPost post = x.post(blog, "Kept", topic, null);
        em.flush();
        assertThat(blog.getNextFetchAt()).isNotNull();

        MyExternalBlogResponse released = service.release(owner.getId(), blog.getId(), false);

        assertThat(released.status()).isEqualTo(ExternalBlogStatus.RELEASED);
        assertThat(released.postCount()).isEqualTo(1);
        em.flush();
        em.clear();
        ExternalBlog saved = blogRepository.findById(blog.getId()).orElseThrow();
        assertThat(saved.getNextFetchAt()).isNull();
        assertThat(postRepository.findById(post.getId()).orElseThrow().isActive()).isTrue();
        assertThat(visible(post)).as("남긴 글은 포털에 그대로").isTrue();
        // 수집 대상이 아님(스케줄러가 고르지 않음), 같은 피드를 다시 신청할 수 있음
        assertThat(blogQueries.findDueIds(JpaFixtures.T0.plusSeconds(3600 * 24 * 365), 10)).doesNotContain(blog.getId());
        assertThat(blogRepository.findHolding(saved.getFeedUrlHash())).isEmpty();
        verify(events).publishEvent(new PortalChangedEvent("external:release"));
        verify(events, never()).publishEvent(any(ThumbnailDeleteRequested.class));
    }

    @Test
    void deleteRemovesRowsAtOnceAndThumbnailsAfterCommit() {
        ExternalBlog blog = x.blog(owner, topic, ExternalBlogStatus.PAUSED);
        ExternalPost withThumb = x.post(blog, "Thumb", topic, null);
        withThumb.attachThumbnail("AbCdEfGhIjKlMnOpQrStUv");
        ExternalPost excluded = x.post(blog, "Excluded", topic, null);
        ExternalPost removed = x.removed(blog, "Removed", topic, RemovedReason.REPORT);
        ClassificationReview review = x.review(withThumb, null, 0.1);
        em.persist(new ExternalPostDailyClick(withThumb, LocalDate.of(2026, 10, 1), 3));
        exclusionRepository.save(new PortalExclusion(excluded, "spam", admin));
        ExternalBlog other = x.blog(f.user("other"), topic, ExternalBlogStatus.ACTIVE);
        ExternalPost untouched = x.post(other, "Other", topic, null);
        em.flush();

        MyExternalBlogResponse released = service.release(owner.getId(), blog.getId(), true);

        assertThat(released.status()).isEqualTo(ExternalBlogStatus.RELEASED);
        assertThat(released.postCount()).isZero();
        em.flush();
        em.clear();
        assertThat(postRepository.findIdsByBlog(blog.getId())).isEmpty();
        assertThat(postRepository.findById(untouched.getId())).isPresent();
        assertThat(em.find(ClassificationReview.class, review.getId())).isNull();
        assertThat(count("ExternalPostDailyClick")).isZero();
        assertThat(exclusionRepository.findByExternalPostId(excluded.getId())).isEmpty();
        assertThat(postRepository.findById(removed.getId())).isEmpty();
        verify(events).publishEvent(new ThumbnailDeleteRequested(List.of("AbCdEfGhIjKlMnOpQrStUv")));
        verify(events).publishEvent(new PortalChangedEvent("external:release-delete"));
    }

    @Test
    void releasedRegistrationCanDeleteKeptPostsLaterButNotKeepAgain() {
        ExternalBlog blog = x.blog(owner, topic, ExternalBlogStatus.RELEASED);
        ExternalPost kept = x.post(blog, "Kept", topic, null);
        em.flush();

        BusinessException again = catchThrowableOfType(BusinessException.class,
                () -> service.release(owner.getId(), blog.getId(), false));
        assertThat(again.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT);
        assertThat(again.params()).containsEntry("status", "RELEASED").containsEntry("action", "release");

        MyExternalBlogResponse deleted = service.release(owner.getId(), blog.getId(), true);
        assertThat(deleted.status()).isEqualTo(ExternalBlogStatus.RELEASED);
        assertThat(postRepository.findById(kept.getId())).isEmpty();
    }

    @Test
    void pendingAndStoppedCanBeReleasedOthersConflict() {
        for (ExternalBlogStatus status : List.of(ExternalBlogStatus.PENDING, ExternalBlogStatus.STOPPED)) {
            ExternalBlog blog = x.blog(owner, topic, status);
            em.flush();
            assertThat(service.release(owner.getId(), blog.getId(), false).status())
                    .isEqualTo(ExternalBlogStatus.RELEASED);
        }
        for (ExternalBlogStatus status : List.of(ExternalBlogStatus.BLOCKED, ExternalBlogStatus.REJECTED)) {
            ExternalBlog blog = x.blog(owner, topic, status);
            em.flush();
            BusinessException conflict = catchThrowableOfType(BusinessException.class,
                    () -> service.release(owner.getId(), blog.getId(), true));
            assertThat(conflict.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT);
        }
    }

    @Test
    void choiceIsRequiredAndOnlyManagerMayRelease() {
        ExternalBlog blog = x.blog(owner, topic, ExternalBlogStatus.ACTIVE);
        em.flush();

        BusinessException missing = catchThrowableOfType(BusinessException.class,
                () -> service.release(owner.getId(), blog.getId(), null));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(missing.fieldErrors()).singleElement().satisfies(fe -> {
            assertThat(fe.field()).isEqualTo("deletePosts");
            assertThat(fe.code()).isEqualTo("REQUIRED");
        });
        BusinessException stranger = catchThrowableOfType(BusinessException.class,
                () -> service.release(admin.getId(), blog.getId(), false));
        assertThat(stranger.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
        BusinessException adminDirect = catchThrowableOfType(BusinessException.class,
                () -> service.release(owner.getId(), x.blog(null, topic, ExternalBlogStatus.ACTIVE).getId(), false));
        assertThat(adminDirect.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
        assertThat(blogRepository.findById(blog.getId()).orElseThrow().getStatus())
                .isEqualTo(ExternalBlogStatus.ACTIVE);
    }
}
