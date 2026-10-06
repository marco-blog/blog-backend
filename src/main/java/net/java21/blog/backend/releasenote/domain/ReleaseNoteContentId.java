package net.java21.blog.backend.releasenote.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** {@link ReleaseNoteContent}의 복합 키(release_note_id, lang). */
@Embeddable
public record ReleaseNoteContentId(
        @Column(name = "release_note_id") Long releaseNoteId,
        @Column(name = "lang", length = 5) String lang) implements Serializable {
}
