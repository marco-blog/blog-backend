package net.java21.blog.backend.media.dto;

/** {@code POST /media} 201 응답(contracts/api.md 이미지 절). */
public record MediaUploadResponse(String key, String url, String mime, long size, int width, int height) {
}
