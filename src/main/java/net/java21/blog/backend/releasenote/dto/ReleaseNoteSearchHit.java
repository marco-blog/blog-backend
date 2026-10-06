package net.java21.blog.backend.releasenote.dto;

import java.time.LocalDate;

/** 검색 결과 한 줄. {@code snippet}은 일치 부분 앞뒤 일반 텍스트(최대 160자). */
public record ReleaseNoteSearchHit(String version, String title, String snippet, LocalDate releaseDate, String lang) {
}
