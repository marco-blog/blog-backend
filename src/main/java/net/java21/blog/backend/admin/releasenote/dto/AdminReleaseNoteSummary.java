package net.java21.blog.backend.admin.releasenote.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;

/** 관리 목록 한 줄({@code langs}는 있는 언어판, ko·en·ja·zh-CN 순). */
public record AdminReleaseNoteSummary(Long id, String version, ReleaseNoteStatus status, LocalDate releaseDate,
        List<String> langs, int revisionNo, Instant firstPublishedAt, Instant publishedAt, Instant updatedAt) {
}
