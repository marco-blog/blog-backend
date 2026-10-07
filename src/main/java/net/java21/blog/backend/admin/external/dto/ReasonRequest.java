package net.java21.blog.backend.admin.external.dto;

/** 사유가 있는 동작(거절·일시 중지·차단·내림·포털 제외). */
public record ReasonRequest(String reason) {
}
