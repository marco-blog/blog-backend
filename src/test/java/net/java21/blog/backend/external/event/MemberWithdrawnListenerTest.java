package net.java21.blog.backend.external.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.event.MemberWithdrawnEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 007 T074: 탈퇴한 회원의 외부 블로그(FR-157, SC-020, research E16). 해제할 수 있는 등록은 RELEASED, 그 회원의 모든 등록(남긴 글 포함)의
 * 노출 중인 글은 {@code MEMBER_WITHDRAWN}, 다른 회원 등록은 그대로, 이후 같은 피드를 새로 신청할 수 있다. 같은 트랜잭션에서 동기로 돈다
 * ({@code AccountServiceTest}가 발행을, 이 시험이 효과를 본다).
 */
@JpaRepositoryTest
class MemberWithdrawnListenerTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private ExternalPostRepository postRepository;

    private ApplicationEventPublisher events;
    private MemberWithdrawnListener listener;
    private ExternalFixtures x;
    private JpaFixtures f;
    private Topic topic;
    private User leaving;

    @BeforeEach
    void setUp() {
        f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
        leaving = f.user("leaving");
        events = mock(ApplicationEventPublisher.class);
        listener = new MemberWithdrawnListener(blogRepository, postRepository, events);
    }

    private ExternalPost reload(ExternalPost post) {
        return postRepository.findById(post.getId()).orElseThrow();
    }

    private ExternalBlogStatus statusOf(ExternalBlog blog) {
        return blogRepository.findById(blog.getId()).orElseThrow().getStatus();
    }

    @Test
    void releasesRegistrationsAndTakesDownAllTheirPosts() {
        ExternalBlog active = x.blog(leaving, topic, ExternalBlogStatus.ACTIVE);
        ExternalPost activePost = x.post(active, "active", topic, null);
        ExternalBlog pending = x.blog(leaving, topic, ExternalBlogStatus.PENDING);
        ExternalBlog paused = x.blog(leaving, topic, ExternalBlogStatus.PAUSED);
        ExternalBlog released = x.blog(leaving, topic, ExternalBlogStatus.RELEASED);
        ExternalPost keptPost = x.post(released, "kept", topic, null);
        ExternalPost reported = x.removed(released, "reported", topic, RemovedReason.REPORT);
        ExternalBlog blocked = x.blog(leaving, topic, ExternalBlogStatus.BLOCKED);
        ExternalBlog rejected = x.blog(leaving, topic, ExternalBlogStatus.REJECTED);
        ExternalBlog others = x.blog(f.user("stays"), topic, ExternalBlogStatus.ACTIVE);
        ExternalPost othersPost = x.post(others, "others", topic, null);
        em.flush();

        listener.on(new MemberWithdrawnEvent(leaving.getId(), JpaFixtures.T0.plusSeconds(10)));
        em.flush();
        em.clear();

        assertThat(statusOf(active)).isEqualTo(ExternalBlogStatus.RELEASED);
        assertThat(statusOf(pending)).isEqualTo(ExternalBlogStatus.RELEASED);
        assertThat(statusOf(paused)).isEqualTo(ExternalBlogStatus.RELEASED);
        assertThat(statusOf(blocked)).isEqualTo(ExternalBlogStatus.BLOCKED);
        assertThat(statusOf(rejected)).isEqualTo(ExternalBlogStatus.REJECTED);
        assertThat(blogRepository.findById(active.getId()).orElseThrow().getNextFetchAt()).isNull();
        for (ExternalPost post : new ExternalPost[] {activePost, keptPost}) {
            ExternalPost saved = reload(post);
            assertThat(saved.getStatus()).isEqualTo(ExternalPostStatus.REMOVED);
            assertThat(saved.getRemovedReason()).isEqualTo(RemovedReason.MEMBER_WITHDRAWN);
        }
        assertThat(reload(reported).getRemovedReason()).isEqualTo(RemovedReason.REPORT);
        assertThat(reload(othersPost).isActive()).isTrue();
        assertThat(statusOf(others)).isEqualTo(ExternalBlogStatus.ACTIVE);
        // 해제됐으니 다른 회원이 같은 피드를 새로 신청할 수 있다
        assertThat(blogRepository.findHolding(active.getFeedUrlHash())).isEmpty();
        verify(events).publishEvent(new PortalChangedEvent("external:member-withdrawn"));
    }

    @Test
    void memberWithoutExternalBlogsChangesNothing() {
        listener.on(new MemberWithdrawnEvent(leaving.getId(), JpaFixtures.T0));
        ExternalBlog pendingOnly = x.blog(f.user("solo"), topic, ExternalBlogStatus.PENDING);
        em.flush();
        listener.on(new MemberWithdrawnEvent(pendingOnly.getMember().getId(), JpaFixtures.T0));
        assertThat(statusOf(pendingOnly)).isEqualTo(ExternalBlogStatus.RELEASED);
        verify(events, never()).publishEvent(any(PortalChangedEvent.class));
    }
}
