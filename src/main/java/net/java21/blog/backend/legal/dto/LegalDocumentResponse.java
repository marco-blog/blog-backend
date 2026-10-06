package net.java21.blog.backend.legal.dto;

import java.time.LocalDate;

/**
 * 약관·개인정보처리방침(contracts/api.md legal 절).
 *
 * @param version           4개 언어 공통 버전({@code blog.legal.terms-version}). 가입 요청의 {@code termsVersion}으로 보낸다
 * @param lang              실제로 준 언어판(요청 언어판이 없으면 en, en도 없으면 ko)
 * @param authoritativeLang 내용이 다를 때 우선하는 언어판(항상 ko, FR-155)
 * @param effectiveAt       시행일. 버전이 날짜(yyyy-MM-dd)면 그 날짜, 아니면 null
 * @param contentHtml       Markdown 본문을 글 본문과 같은 규칙으로 변환·살균한 HTML
 */
public record LegalDocumentResponse(String version, String lang, String authoritativeLang, LocalDate effectiveAt,
        String contentHtml) {
}
