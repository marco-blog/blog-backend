package net.java21.blog.backend.releasenote.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 001 contracts {@code ReleaseNoteSummary}. {@code lang}은 실제로 준 언어판. */
public record ReleaseNoteSummary(String version, String title, LocalDate releaseDate, Instant firstPublishedAt,
        String lang) {
}
