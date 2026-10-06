package net.java21.blog.backend.releasenote.dto;

/** 이전·다음 노트({@code { version, title }}, 제목은 같은 언어 대체 규칙). */
public record VersionRef(String version, String title) {
}
