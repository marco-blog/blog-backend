package net.java21.blog.backend.user.domain;

/** 회원 권한(users.role). 관리자 API는 요청마다 DB의 현재 값을 다시 확인한다(006 FR-105). */
public enum UserRole {
    USER,
    ADMIN,
    SUPER_ADMIN
}
