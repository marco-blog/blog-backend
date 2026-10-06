package net.java21.blog.backend.admin;

/**
 * 관리자 API 요청마다 회원의 현재 권한을 DB에서 다시 읽는다(contracts/api.md "관리자 API 공통 규칙", 006 FR-097).
 * JWT의 role 클레임은 믿지 않는다.
 */
@FunctionalInterface
public interface AdminRoleLookup {

    /** 이 회원이 지금 관리자(ADMIN·SUPER_ADMIN)이고 정상(ACTIVE) 상태인지. */
    boolean isActiveAdmin(long userId);
}
