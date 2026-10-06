package net.java21.blog.backend.releasenote.dto;

import java.util.List;

/**
 * {@code GET /release-notes}: 게시 노트 전체(버전 내림차순)와 포털 카드. 카드는 최신 노트의 처음 게시가
 * {@code blog.release-notes.portal-card-days} 이내일 때만(003 FR-162).
 */
public record ReleaseNoteListResponse(List<ReleaseNoteSummary> items, ReleaseNoteSummary portalCard) {
}
