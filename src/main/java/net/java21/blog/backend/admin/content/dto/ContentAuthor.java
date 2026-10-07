package net.java21.blog.backend.admin.content.dto;

import net.java21.blog.backend.user.domain.UserStatus;

/**
 * 콘텐츠 관리 목록의 작성자(006 contracts/api.md {@code AdminRef}: userId·nickname·status). 이메일은 넣지 않는다(FR-104).
 * 003의 {@code admin.portal.dto.AdminRef}(관리자 표시용, status 없음)와 이름이 겹치지 않게 Java 이름을 따로 둔다.
 */
public record ContentAuthor(long userId, String nickname, UserStatus status) {
}
