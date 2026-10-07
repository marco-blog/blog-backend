package net.java21.blog.backend.admin.user.dto;

/**
 * {@code PUT /admin/users/{id}/role} {@code { role: "USER" | "ADMIN" | "SUPER_ADMIN" }}(006 FR-105). 문자열로 받아 서비스가
 * 400 {@code REQUIRED}·{@code INVALID}를 정한다(열거형 역직렬화 오류 대신 필드 오류).
 */
public record RoleChangeRequest(String role) {
}
