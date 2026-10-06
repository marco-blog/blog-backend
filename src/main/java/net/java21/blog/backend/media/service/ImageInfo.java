package net.java21.blog.backend.media.service;

/**
 * 파일 내용으로 판별한 이미지 정보.
 *
 * @param mime      image/jpeg·png·gif·webp
 * @param extension 저장 파일 확장자(jpg·png·gif·webp)
 */
public record ImageInfo(String mime, String extension, int width, int height) {
}
