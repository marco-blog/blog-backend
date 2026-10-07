package net.java21.blog.backend.releasenote.repository;

/** 노트의 언어판 하나의 제목(목록·이전·다음·배너용, 본문은 읽지 않는다). */
public record ContentTitleRow(Long noteId, String lang, String title) {
}
