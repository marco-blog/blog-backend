package net.java21.blog.backend.releasenote.dto;

import java.time.Instant;

/** 독자 "수정 이력" 한 줄(수정한 관리자는 주지 않음, 003 FR-166). */
public record ReleaseNoteRevisionItem(int revisionNo, Instant editedAt) {
}
