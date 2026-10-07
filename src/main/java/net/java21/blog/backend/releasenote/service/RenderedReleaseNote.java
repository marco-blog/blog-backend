package net.java21.blog.backend.releasenote.service;

import java.util.List;

import net.java21.blog.backend.releasenote.domain.TocEntry;

/**
 * 릴리스 노트 언어판 변환 결과(003 research P11).
 *
 * @param html 살균된 HTML(h2~h4에 앵커 {@code id})
 * @param text 태그를 뺀 텍스트(검색·일치 부분 표시용)
 * @param toc  같은 앵커로 만든 목차(h2~h4)
 */
public record RenderedReleaseNote(String html, String text, List<TocEntry> toc) {

    public static final RenderedReleaseNote EMPTY = new RenderedReleaseNote("", "", List.of());
}
