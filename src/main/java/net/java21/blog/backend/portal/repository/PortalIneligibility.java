package net.java21.blog.backend.portal.repository;

/** 글이 포털 노출 조건(003 FR-088)을 만족하지 않는 이유(research P1). 관리자 화면·추천 검증에 쓴다. */
public enum PortalIneligibility {
    /** 001 "본문 노출 가능"이 아님(비공개·임시·삭제·숨김·보호 글, 정지·탈퇴 회원, 삭제된 블로그). */
    NOT_BODY_VISIBLE,
    /** 블로그의 "포털에 내 글 노출"이 꺼짐(FR-089). */
    BLOG_PORTAL_DISABLED,
    /** 운영자가 포털에서 제외함(FR-093). */
    EXCLUDED,
    /** 글쓴이 가입 후 대기 시간이 지나지 않음. */
    NEW_MEMBER,
    /** 본문 텍스트가 최소 길이보다 짧음. */
    TOO_SHORT
}
