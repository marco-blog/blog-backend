package net.java21.blog.backend.i18n;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * 메일 문구용 {@link MessageSource}(research R22·R23). API 응답에는 번역 문구를 넣지 않는다(오류는 코드만).
 * <ul>
 *   <li>파일: {@code messages_ko/en/ja/zh_CN.properties}(UTF-8). 키 집합이 같은지는 {@code MessageKeysConsistencyTest}가 확인한다.</li>
 *   <li>지원하지 않는 언어는 en, 그 언어 파일에 키가 없으면 ko(기준 언어) 문구를 쓴다. 서버 시스템 언어는 보지 않는다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class I18nConfig {

    static final String BASENAME = "messages";

    @Bean
    public MessageSource messageSource() {
        return create(BASENAME);
    }

    static MessageSource create(String basename) {
        ResourceBundleMessageSource korean = bundle(basename, Locale.KOREAN);
        ResourceBundleMessageSource messages = bundle(basename, Locale.ENGLISH);
        messages.setParentMessageSource(new FixedLocaleMessageSource(korean, Locale.KOREAN));
        return messages;
    }

    private static ResourceBundleMessageSource bundle(String basename, Locale defaultLocale) {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(basename);
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(defaultLocale);
        return source;
    }

    /** 요청 언어와 상관없이 정해진 언어로 찾는다(마지막 대체 언어 ko). */
    private record FixedLocaleMessageSource(MessageSource delegate, Locale locale) implements MessageSource {

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale ignored) {
            return delegate.getMessage(code, args, defaultMessage, locale);
        }

        @Override
        public String getMessage(String code, Object[] args, Locale ignored) throws NoSuchMessageException {
            return delegate.getMessage(code, args, locale);
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale ignored) throws NoSuchMessageException {
            return delegate.getMessage(resolvable, locale);
        }
    }
}
