package net.java21.blog.backend.admin.releasenote.dto;

import java.time.LocalDate;
import java.util.Map;

/** 001 contracts {@code ReleaseNoteWrite}(만들기). 키는 {@code ko}·{@code en}·{@code ja}·{@code zh-CN}, ko 필수. */
public record ReleaseNoteWriteRequest(String version, LocalDate releaseDate, Map<String, ContentWrite> contents) {
}
