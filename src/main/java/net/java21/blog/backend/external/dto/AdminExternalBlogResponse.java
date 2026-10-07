package net.java21.blog.backend.external.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;

/** 관리자용 외부 블로그(007 contracts/api.md AdminExternalBlog = MyExternalBlog + 운영 정보). */
public record AdminExternalBlogResponse(@JsonUnwrapped MyExternalBlogResponse base, Member member,
        String registrationBasis, AdminRef reviewedBy, Instant reviewedAt, Instant ownershipVerifiedAt,
        Instant nextFetchAt, Integer lastHttpStatus, int consecutiveFailures, Instant firstFailedAt,
        long pendingReviewCount) {

    public record Member(long userId, String nickname, UserStatus status) {
    }

    public static AdminExternalBlogResponse of(ExternalBlog b, long postCount, long pendingReviewCount) {
        User m = b.getMember();
        User r = b.getReviewedBy();
        return new AdminExternalBlogResponse(MyExternalBlogResponse.of(b, postCount),
                m == null ? null : new Member(m.getId(), m.getNickname(), m.getStatus()), b.getRegistrationBasis(),
                r == null ? null : new AdminRef(r.getId(), r.getNickname()), b.getReviewedAt(),
                b.getOwnershipVerifiedAt(), b.getNextFetchAt(), b.getLastHttpStatus(), b.getConsecutiveFailures(),
                b.getFirstFailedAt(), pendingReviewCount);
    }
}
