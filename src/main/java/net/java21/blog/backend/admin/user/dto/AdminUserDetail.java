package net.java21.blog.backend.admin.user.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;

/**
 * 관리자 회원 상세(005 contracts/api.md {@code AdminUserDetail}, 006 FR-104). 이메일 원문·비밀번호·비공개 글 본문은 주지 않는다.
 * {@code blogCount}·{@code blogLimit.current}는 ACTIVE 블로그 수, {@code blogs}는 삭제된 블로그를 포함한다.
 */
public record AdminUserDetail(Long id, String nickname, UserStatus status, UserRole role, Instant createdAt,
        long blogCount, long postCount, long receivedReportCount, Instant lastLoginAt, List<BlogItem> blogs,
        BlogLimit blogLimit) {

    public record BlogItem(String handle, String title, BlogStatus status) {
    }

    /** 003 블로그 한도 응답과 같은 값. {@code custom}이면 회원별 한도. */
    public record BlogLimit(long current, int limit, boolean custom) {
    }
}
