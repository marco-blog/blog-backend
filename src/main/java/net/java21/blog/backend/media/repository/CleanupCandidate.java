package net.java21.blog.backend.media.repository;

import net.java21.blog.backend.media.domain.MediaStatus;

/** 정리 작업 대상(만료된 TEMP 또는 ORPHANED). */
public record CleanupCandidate(Long id, String mediaKey, MediaStatus status, String storedPath) {
}
