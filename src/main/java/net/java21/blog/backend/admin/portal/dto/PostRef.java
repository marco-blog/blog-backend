package net.java21.blog.backend.admin.portal.dto;

/** 관리 화면의 글 참조({@code { id, title, blogHandle }}). */
public record PostRef(Long id, String title, String blogHandle) {
}
