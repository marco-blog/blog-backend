package net.java21.blog.backend.user.domain;

/** 회원 상태(users.status). ACTIVE ↔ SUSPENDED(관리자), ACTIVE → WITHDRAWN(본인, 되돌릴 수 없음). */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    WITHDRAWN
}
