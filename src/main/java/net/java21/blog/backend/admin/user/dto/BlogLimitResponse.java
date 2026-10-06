package net.java21.blog.backend.admin.user.dto;

/**
 * 회원 블로그 한도 변경 결과(006 FR-160).
 *
 * @param blogCount      삭제하지 않은 블로그 수
 * @param maxBlogs       회원별 한도({@code null}=기본값)
 * @param effectiveLimit 실제 적용되는 한도
 */
public record BlogLimitResponse(Long userId, long blogCount, Integer maxBlogs, int effectiveLimit) {
}
