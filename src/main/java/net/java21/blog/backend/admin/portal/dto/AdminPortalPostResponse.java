package net.java21.blog.backend.admin.portal.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;

/**
 * 콘솔의 글 찾기({@code GET /admin/portal/posts/{id}}): 제목·블로그·상태와 포털 노출 여부·이유, 제외 정보.
 *
 * @param ineligibleReasons {@code PortalIneligibility} 이름들(노출 가능하면 빈 목록)
 */
public record AdminPortalPostResponse(Long id, String title, BlogRef blog, PostStatus status,
        PostVisibility visibility, Instant publishedAt, boolean portalEligible, List<String> ineligibleReasons,
        Excluded excluded) {

    public record BlogRef(String handle, String title) {
    }

    public record Excluded(String reason, AdminRef excludedBy, Instant createdAt) {
    }
}
