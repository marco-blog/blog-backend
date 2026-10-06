package net.java21.blog.backend.releasenote.domain;

/** 수정본에 저장하는 언어판 하나(006 data-model {@code contents_json}의 값). HTML은 볼 때 다시 변환한다. */
public record RevisionContent(String title, String contentMarkdown) {
}
