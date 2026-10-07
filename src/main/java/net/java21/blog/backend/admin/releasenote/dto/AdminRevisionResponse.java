package net.java21.blog.backend.admin.releasenote.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;

/**
 * 관리 수정본. 목록은 {@code { revisionNo, editedBy, editedAt, status }}만, 한 수정본 상세는 ReleaseNoteWrite({@code version},
 * {@code releaseDate}, {@code contents})까지 준다. 목록에서 빈 칸은 응답에서 뺀다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminRevisionResponse(int revisionNo, AdminRef editedBy, Instant editedAt, ReleaseNoteStatus status,
        String version, LocalDate releaseDate, Map<String, ContentWrite> contents) {
}
