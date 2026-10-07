package net.java21.blog.backend.external.member;

import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.dto.MyExternalBlogResponse;
import net.java21.blog.backend.external.release.ExternalPostPurger;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 해제(007 FR-126, US4 AS1, research E16, 결정 표 24번). 관리 회원이 남길지 지울지 고른다.
 * <ul>
 *   <li>PENDING·ACTIVE·PAUSED·STOPPED → RELEASED, 수집 즉시 중지({@code next_fetch_at} NULL).</li>
 *   <li>{@code deletePosts=false}: 수집된 글은 그대로(RELEASED 등록의 ACTIVE 글 = 남긴 글, 포털에 계속 노출, 기한 없음).</li>
 *   <li>{@code deletePosts=true}: 그 등록의 글 행을 같은 트랜잭션에서 모두 삭제(30일 보관 없음, 주인의 삭제 의사가 우선),
 *   썸네일은 커밋 뒤. 그 글에 걸린 처리 중 신고는 005 화면에 "대상 없음"으로 남는다.</li>
 *   <li>이미 RELEASED: {@code true}면 남긴 글 삭제(상태 그대로), {@code false}면 409.</li>
 * </ul>
 * 커밋 뒤 포털 캐시를 비운다.
 */
@Service
public class ReleaseService {

    private final ExternalBlogRepository blogRepository;
    private final ExternalPostRepository postRepository;
    private final ExternalPostPurger purger;
    private final ApplicationEventPublisher events;

    public ReleaseService(ExternalBlogRepository blogRepository, ExternalPostRepository postRepository,
            ExternalPostPurger purger, ApplicationEventPublisher events) {
        this.blogRepository = blogRepository;
        this.postRepository = postRepository;
        this.purger = purger;
        this.events = events;
    }

    @Transactional
    public MyExternalBlogResponse release(long userId, long id, Boolean deletePosts) {
        if (deletePosts == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("deletePosts", "REQUIRED")));
        }
        ExternalBlog blog = blogRepository.findById(id).filter(b -> b.isManagedBy(userId))
                .orElseThrow(() -> MemberExternalBlogService.notFound(id));
        if (blog.getStatus() == ExternalBlogStatus.RELEASED) {
            if (!deletePosts) {
                throw blog.conflict("release", Map.of());
            }
        } else {
            blog.release();
            blogRepository.flush();
        }
        if (deletePosts) {
            purger.purge(postRepository.findPurgeRows(id));
        }
        events.publishEvent(new PortalChangedEvent(deletePosts ? "external:release-delete" : "external:release"));
        ExternalBlog saved = blogRepository.findById(id).orElseThrow();
        return MyExternalBlogResponse.of(saved,
                postRepository.countByExternalBlogIdAndStatus(id, ExternalPostStatus.ACTIVE));
    }
}
