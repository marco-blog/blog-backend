package net.java21.blog.backend.admin.releasenote.dto;

import java.time.LocalDate;
import java.util.Map;

/** {@code PUT /admin/release-notes/{id}}: ReleaseNoteWrite + {@code baseRevisionNo}(낙관적 잠금, 필수). 빠진 언어판은 지운다. */
public record UpdateReleaseNoteRequest(String version, LocalDate releaseDate, Map<String, ContentWrite> contents,
        Integer baseRevisionNo) {

    public ReleaseNoteWriteRequest write() {
        return new ReleaseNoteWriteRequest(version, releaseDate, contents);
    }
}
