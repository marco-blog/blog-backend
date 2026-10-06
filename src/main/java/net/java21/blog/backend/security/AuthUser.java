package net.java21.blog.backend.security;

/**
 * 접근 토큰으로 인증된 회원. 컨트롤러는 {@link CurrentUser}로 받는다.
 *
 * @param userId   회원 ID. 일반 API의 소유자 검증은 이 값으로 한다.
 * @param role     {@code USER}·{@code ADMIN}·{@code SUPER_ADMIN}. 화면 메뉴 표시용 힌트이며,
 *                 관리자 API는 요청마다 DB의 현재 role을 다시 확인한다(contracts/api.md).
 * @param familyId 로그인 계열(refresh_tokens.family_id, UUID). 비밀번호 변경 때 현재 기기 계열을 남기는 데 쓴다(research R2).
 */
public record AuthUser(long userId, String role, String familyId) {
}
