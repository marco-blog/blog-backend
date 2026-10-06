package net.java21.blog.backend.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 메일 문구 MessageSource: 회원 언어 → 지원하지 않는 언어는 en → 그 언어에 없는 키는 ko. */
class I18nConfigTest {

    private final MessageSource messages = new I18nConfig().messageSource();

    @Test
    void resolvesEachSupportedLocaleInUtf8() {
        assertThat(messages.getMessage("mail.site-name", null, Locale.KOREAN)).isEqualTo("블로그");
        assertThat(messages.getMessage("mail.site-name", null, Locale.ENGLISH)).isEqualTo("Blog");
        assertThat(messages.getMessage("mail.site-name", null, Locale.JAPANESE)).isEqualTo("ブログ");
        assertThat(messages.getMessage("mail.site-name", null, Locale.SIMPLIFIED_CHINESE)).isEqualTo("博客");
        assertThat(messages.getMessage("mail.site-name", null, Locale.forLanguageTag("zh-CN"))).isEqualTo("博客");
    }

    @Test
    void unsupportedLocaleFallsBackToEnglish() {
        assertThat(messages.getMessage("mail.site-name", null, Locale.FRENCH)).isEqualTo("Blog");
    }

    @Test
    void keyMissingInLocaleFallsBackToKorean() {
        MessageSource test = I18nConfig.create("i18n-test/messages");
        assertThat(test.getMessage("only.ko", null, Locale.JAPANESE)).isEqualTo("한국어만");
        assertThat(test.getMessage("only.ko", null, Locale.FRENCH)).isEqualTo("한국어만");
        assertThat(test.getMessage("both", null, Locale.ENGLISH)).isEqualTo("en");
    }

    @Test
    void unknownKeyFails() {
        assertThatThrownBy(() -> messages.getMessage("no.such.key", null, Locale.KOREAN))
                .isInstanceOf(NoSuchMessageException.class);
    }
}
