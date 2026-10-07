package net.java21.blog.backend.admin.releasenote.dto;

/** 언어판 하나({@code { title, contentMarkdown }}). 제목 1~200자, 본문 1~100,000자(서비스 검증). */
public record ContentWrite(String title, String contentMarkdown) {
}
