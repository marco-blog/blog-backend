package net.java21.blog.backend.admin.portal.dto;

/** 작업한 관리자({@code { userId, nickname }}). */
public record AdminRef(Long userId, String nickname) {
}
