package net.java21.blog.backend.admin.setting.dto;

/** 설정값 저장({@code PUT /admin/settings/{key}}). 값의 모양(객체·문자열·숫자)은 키마다 다르고 서비스가 검증한다. */
public record SettingValueRequest(Object value) {
}
