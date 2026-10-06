package net.java21.blog.backend.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/** 메일 문구 4개 언어 파일의 키 집합이 같고 빈 값이 없다(SC-024, R22, 원칙 VII). 기준은 ko. */
class MessageKeysConsistencyTest {

    static final List<String> LOCALES = List.of("ko", "en", "ja", "zh_CN");

    @Test
    void allLocaleFilesExist() {
        for (String locale : LOCALES) {
            assertThat(new ClassPathResource(file(locale)).exists()).as(file(locale)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ja", "zh_CN"})
    void hasSameKeysAsKorean(String locale) throws IOException {
        Set<String> ko = load("ko").stringPropertyNames();
        Set<String> other = load(locale).stringPropertyNames();

        Set<String> missing = new TreeSet<>(ko);
        missing.removeAll(other);
        Set<String> extra = new TreeSet<>(other);
        extra.removeAll(ko);

        assertThat(missing).as("%s에 없는 키", file(locale)).isEmpty();
        assertThat(extra).as("%s에만 있는 키(ko에 먼저 넣는다)", file(locale)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ko", "en", "ja", "zh_CN"})
    void hasNoBlankValues(String locale) throws IOException {
        Properties properties = load(locale);
        Set<String> blank = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (properties.getProperty(key).isBlank()) {
                blank.add(key);
            }
        }
        assertThat(blank).as("%s의 빈 값", file(locale)).isEmpty();
    }

    static Properties load(String locale) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(file(locale)).getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static String file(String locale) {
        return "messages_" + locale + ".properties";
    }
}
