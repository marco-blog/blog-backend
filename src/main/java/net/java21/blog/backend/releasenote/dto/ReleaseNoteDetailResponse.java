package net.java21.blog.backend.releasenote.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import net.java21.blog.backend.releasenote.domain.TocEntry;

/**
 * 001 contracts {@code ReleaseNoteDetail}. 수정본 상세({@code /revisions/{revisionNo}})도 같은 모양이며 {@code revisionNo}가 그 번호다
 * (지금 내용은 현재 수정본 번호).
 *
 * @param requestedLang 요청한 언어
 * @param lang          실제로 준 언어판(요청 → en → ko)
 * @param revisionCount 처음 게시한 때부터의 수정본 수(2 이상이면 "수정 이력"을 보인다)
 */
public record ReleaseNoteDetailResponse(String version, LocalDate releaseDate, Instant firstPublishedAt,
        Instant updatedAt, String requestedLang, String lang, String title, String contentHtml, List<TocEntry> toc,
        VersionRef prev, VersionRef next, long revisionCount, int revisionNo) {
}
