package net.java21.blog.backend.admin.setting.dto;

import java.time.Instant;

import net.java21.blog.backend.admin.portal.dto.AdminRef;

/**
 * 운영 설정 하나(003 contracts/api.md {@code Setting}).
 *
 * @param value        지금 쓰는 값(행이 없으면 기본 프로퍼티 값)
 * @param defaultValue 프로퍼티 값
 * @param overridden   {@code system_settings} 행이 있는지
 */
public record SettingResponse(String key, Object value, Object defaultValue, boolean overridden, AdminRef updatedBy,
        Instant updatedAt) {
}
