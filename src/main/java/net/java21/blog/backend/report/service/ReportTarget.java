package net.java21.blog.backend.report.service;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.user.domain.User;

/**
 * 처리기가 읽은 신고 대상(005 research M4).
 *
 * @param type       대상 종류
 * @param id         대상 id
 * @param targetUser 대상 작성 회원(비회원 글·밖에서 온 트랙백이면 null) — {@code reports.target_user_id}
 * @param targetBlog 대상이 속한 블로그(밖에서 온 트랙백이면 null) — {@code reports.target_blog_id}
 * @param postId     대상이 속한 글(글·댓글·트랙백, 방명록은 null) — 주소 해석의 소속 확인
 * @param blogHandle 대상이 놓인 블로그 주소 — 주소 해석의 소속 확인
 */
public record ReportTarget(ReportTargetType type, Long id, User targetUser, Blog targetBlog, Long postId,
        String blogHandle) {
}
