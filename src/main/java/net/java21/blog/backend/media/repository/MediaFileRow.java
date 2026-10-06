package net.java21.blog.backend.media.repository;

import net.java21.blog.backend.media.domain.MediaStatus;

/** 이미지 제공에 필요한 값(DTO projection, 엔티티를 읽지 않는다). */
public record MediaFileRow(Long id, String mediaKey, Long ownerId, MediaStatus status, String storedPath, String mime,
        int width, int height) {

    public boolean isTemp() {
        return status == MediaStatus.TEMP;
    }
}
