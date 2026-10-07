package net.java21.blog.backend.admin.user.dto;

/** 정지·해제 사유 {@code { reason }}(정지는 1~500자 필수, 해제는 선택). */
public record SuspendRequest(String reason) {
}
