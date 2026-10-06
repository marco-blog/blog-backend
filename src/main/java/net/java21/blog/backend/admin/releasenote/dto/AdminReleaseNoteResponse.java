package net.java21.blog.backend.admin.releasenote.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;

/** 001 contracts {@code AdminReleaseNote}: ReleaseNoteWrite + 상태·수정본 번호·게시 시각·만든/고친 관리자. */
public record AdminReleaseNoteResponse(Long id, String version, LocalDate releaseDate,
        Map<String, ContentWrite> contents, ReleaseNoteStatus status, int revisionNo, Instant firstPublishedAt,
        Instant publishedAt, AdminRef createdBy, AdminRef updatedBy, Instant createdAt, Instant updatedAt) {
}
