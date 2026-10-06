package net.java21.blog.backend.legal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import net.java21.blog.backend.content.HtmlSanitizerPolicy;
import net.java21.blog.backend.content.MarkdownRenderer;
import net.java21.blog.backend.content.VideoEmbedTransformer;
import net.java21.blog.backend.legal.LegalDocumentType;
import net.java21.blog.backend.legal.LegalProperties;
import net.java21.blog.backend.legal.dto.LegalDocumentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 약관·개인정보처리방침 본문(T129·T143, FR-137·155). 버전은 4개 언어 공통 하나이고 한국어판이 기준이다.
 * 요청 언어판이 없으면 en, en도 없으면 ko를 준다.
 */
class LegalServiceTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer(new HtmlSanitizerPolicy(), new VideoEmbedTransformer());

    private LegalService service(String version, String location) {
        return new LegalService(new LegalProperties(version), renderer, location);
    }

    @Test
    void returnsRequestedLanguageWithCommonVersion() {
        LegalDocumentResponse doc = service("2026-10-06", "legal-test/").document(LegalDocumentType.TERMS, "en");

        assertThat(doc.version()).isEqualTo("2026-10-06");
        assertThat(doc.lang()).isEqualTo("en");
        assertThat(doc.authoritativeLang()).isEqualTo("ko");
        assertThat(doc.effectiveAt()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(doc.contentHtml()).contains("<h1>Terms (test)</h1>", "English text");
    }

    @Test
    void missingLanguageFallsBackToEnglish() {
        LegalDocumentResponse doc = service("2026-10-06", "legal-test/").document(LegalDocumentType.TERMS, "ja");

        assertThat(doc.lang()).isEqualTo("en");
        assertThat(doc.contentHtml()).contains("English text");
    }

    @Test
    void missingEnglishFallsBackToKoreanAndBodyIsSanitized() {
        LegalDocumentResponse doc = service("2026-10-06", "legal-test/").document(LegalDocumentType.PRIVACY, "zh-CN");

        assertThat(doc.lang()).isEqualTo("ko");
        assertThat(doc.contentHtml()).contains("한국어만 있음").doesNotContain("<script", "alert");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "fr", "../terms", "KO"})
    void unsupportedOrMissingLangUsesFallbackChain(String lang) {
        LegalDocumentResponse doc = service("2026-10-06", "legal-test/").document(LegalDocumentType.TERMS, lang);

        assertThat(doc.lang()).isEqualTo("en");
    }

    @Test
    void nullLangUsesFallbackChain() {
        assertThat(service("2026-10-06", "legal-test/").document(LegalDocumentType.TERMS, null).lang())
                .isEqualTo("en");
    }

    @Test
    void versionThatIsNotADateHasNoEffectiveDate() {
        LegalDocumentResponse doc = service("v3", "legal-test/").document(LegalDocumentType.TERMS, "ko");

        assertThat(doc.version()).isEqualTo("v3");
        assertThat(doc.effectiveAt()).isNull();
    }

    @Test
    void noDocumentAtAllIsAnError() {
        assertThatThrownBy(() -> service("2026-10-06", "legal-none/").document(LegalDocumentType.TERMS, "ko"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terms");
    }

    @ParameterizedTest
    @EnumSource(LegalDocumentType.class)
    void productionResourcesExistForAllFourLanguages(LegalDocumentType type) {
        LegalService production = new LegalService(new LegalProperties("2026-10-06"), renderer);
        for (String lang : LegalService.LANGUAGES) {
            LegalDocumentResponse doc = production.document(type, lang);
            assertThat(doc.lang()).as(type + " " + lang).isEqualTo(lang);
            assertThat(doc.contentHtml()).as(type + " " + lang).startsWith("<h1>");
        }
    }
}
