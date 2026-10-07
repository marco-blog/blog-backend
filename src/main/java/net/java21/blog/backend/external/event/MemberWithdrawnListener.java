package net.java21.blog.backend.external.event;

import java.util.ArrayList;
import java.util.List;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.user.event.MemberWithdrawnEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 탈퇴한 회원의 외부 블로그(007 FR-157, SC-020, research E16): 해제할 수 있는 등록(PENDING·ACTIVE·PAUSED·STOPPED)은 RELEASED로,
 * 그 회원의 모든 등록(글을 남기고 해제한 등록 포함)의 노출 중인 글은 {@code REMOVED}({@code MEMBER_WITHDRAWN}). 거절·차단 등록은 그대로
 * (차단은 피드 단위 조치라 풀지 않는다). 같은 트랜잭션({@link EventListener})이라 탈퇴가 롤백되면 함께 롤백된다. 해제됐으므로 다른 회원이
 * 같은 피드를 새로 신청할 수 있고, 탈퇴로 내린 글은 새 등록으로 옮기지 않는다.
 */
@Component
public class MemberWithdrawnListener {

    private final ExternalBlogRepository blogRepository;
    private final ExternalPostRepository postRepository;
    private final ApplicationEventPublisher events;

    public MemberWithdrawnListener(ExternalBlogRepository blogRepository, ExternalPostRepository postRepository,
            ApplicationEventPublisher events) {
        this.blogRepository = blogRepository;
        this.postRepository = postRepository;
        this.events = events;
    }

    @EventListener
    public void on(MemberWithdrawnEvent event) {
        List<ExternalBlog> blogs = blogRepository.findByMemberId(event.userId());
        if (blogs.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (ExternalBlog blog : blogs) {
            if (ExternalBlogStatus.RELEASABLE.contains(blog.getStatus())) {
                blog.release();
            }
            ids.add(blog.getId());
        }
        blogRepository.flush();
        int removed = postRepository.removeAllActive(ids, RemovedReason.MEMBER_WITHDRAWN, event.withdrawnAt());
        if (removed > 0) {
            events.publishEvent(new PortalChangedEvent("external:member-withdrawn"));
        }
    }
}
