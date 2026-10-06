package net.java21.blog.backend.releasenote.domain;

/** 자동 목차 한 항목({@code toc_json}, 003 research P11). {@code level}은 2~4(h2~h4). */
public record TocEntry(int level, String text, String anchor) {
}
