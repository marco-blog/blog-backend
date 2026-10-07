package net.java21.blog.backend.export.dto;

import java.time.Instant;

import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.domain.ExportStatus;

/** 블로그 백업(contracts/api.md {@code BlogExport}). 파일 경로는 싣지 않는다. */
public record BlogExportResponse(Long id, ExportStatus status, Long fileSize, String errorCode, Instant createdAt,
        Instant completedAt, Instant expiresAt) {

    public static BlogExportResponse of(BlogExport export) {
        return new BlogExportResponse(export.getId(), export.getStatus(), export.getFileSize(), export.getErrorCode(),
                export.getCreatedAt(), export.getCompletedAt(), export.getExpiresAt());
    }
}
