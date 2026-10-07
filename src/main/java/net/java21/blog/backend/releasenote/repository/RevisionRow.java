package net.java21.blog.backend.releasenote.repository;

import java.time.Instant;

import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;

/** 수정본 목록 한 줄. 독자 API는 {@code editedBy*}를 주지 않는다(003 FR-166). */
public record RevisionRow(int revisionNo, Instant editedAt, ReleaseNoteStatus status, Long editedById,
        String editedByNickname) {
}
