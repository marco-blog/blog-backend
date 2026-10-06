package net.java21.blog.backend.releasenote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import net.java21.blog.backend.common.error.BusinessException;
import org.junit.jupiter.api.Test;

/** SemVer 숫자 비교와 언어판 고르기·요청 언어 결정(003 T108·T109, FR-161·163). */
class SemVerAndLanguageTest {

    @Test
    void semVerParsesAndComparesNumerically() {
        assertThat(SemVer.parse("1.10.0")).contains(new SemVer(1, 10, 0));
        assertThat(SemVer.parse("1.10.0").orElseThrow().isNewerThan(SemVer.parse("1.9.0").orElseThrow())).isTrue();
        assertThat(new SemVer(1, 2, 0).isNewerThan(new SemVer(1, 2, 0))).isFalse();
        assertThat(new SemVer(2, 0, 0).compareTo(new SemVer(1, 99, 99))).isPositive();
        assertThat(new SemVer(0, 1, 2)).hasToString("0.1.2");
        for (String bad : new String[] {"01.2.0", "1.2", "1.2.0-beta", "v1.2.0", "", "1.2.0.1", "9999999.0.0"}) {
            assertThat(SemVer.parse(bad)).as(bad).isEmpty();
        }
        assertThat(SemVer.parse(null)).isEmpty();
    }

    @Test
    void pickFallsBackToEnglishThenKorean() {
        assertThat(ReleaseNoteLanguage.pick(Set.of("ko", "en", "ja"), "ja")).isEqualTo("ja");
        assertThat(ReleaseNoteLanguage.pick(Set.of("ko", "en"), "ja")).isEqualTo("en");
        assertThat(ReleaseNoteLanguage.pick(Set.of("ko"), "zh-CN")).isEqualTo("ko");
        assertThat(ReleaseNoteLanguage.pick(Set.of(), "ko")).isNull();
    }

    @Test
    void resolveUsesLangThenAcceptLanguageThenKorean() {
        assertThat(ReleaseNoteLanguage.resolve("ja", "en")).isEqualTo("ja");
        assertThat(ReleaseNoteLanguage.resolve(null, "fr-FR, en-US;q=0.8")).isEqualTo("en");
        assertThat(ReleaseNoteLanguage.resolve("", "zh-TW")).isEqualTo("zh-CN");
        assertThat(ReleaseNoteLanguage.resolve(null, "fr")).isEqualTo("ko");
        assertThat(ReleaseNoteLanguage.resolve(null, ";;;=")).isEqualTo("ko");
        assertThat(ReleaseNoteLanguage.resolve(null, null)).isEqualTo("ko");
        assertThatThrownBy(() -> ReleaseNoteLanguage.resolve("fr", null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.fieldErrors().get(0).field()).isEqualTo("lang");
                    assertThat(e.fieldErrors().get(0).params()).containsEntry("allowed", List.of("ko", "en", "ja",
                            "zh-CN"));
                });
    }
}
