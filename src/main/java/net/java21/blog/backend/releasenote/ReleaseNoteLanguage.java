package net.java21.blog.backend.releasenote;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;

/**
 * 릴리스 노트 언어판(003 FR-161, 001 contracts "ReleaseNoteDetail"): {@code ko}·{@code en}·{@code ja}·{@code zh-CN}. 요청 언어판이
 * 없으면 en, en도 없으면 ko(필수). 제목과 본문은 한 언어판 단위로 함께 대체한다.
 */
public final class ReleaseNoteLanguage {

    public static final String KO = "ko";
    public static final String EN = "en";
    public static final List<String> ALL = List.of(KO, EN, "ja", "zh-CN");

    private ReleaseNoteLanguage() {
    }

    /**
     * 요청 언어를 정한다. {@code lang}이 있으면 허용 값이어야 하고(아니면 400 field {@code lang} {@code INVALID},
     * {@code params.allowed}), 없으면 {@code Accept-Language}에서 가장 맞는 것, 그것도 없으면 ko(001 FR-149).
     */
    public static String resolve(String lang, String acceptLanguage) {
        if (lang != null && !lang.isBlank()) {
            if (!ALL.contains(lang)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid lang",
                        List.of(new FieldError("lang", "INVALID", Map.of("allowed", ALL))));
            }
            return lang;
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            try {
                for (Locale.LanguageRange range : Locale.LanguageRange.parse(acceptLanguage)) {
                    String matched = match(range.getRange());
                    if (matched != null) {
                        return matched;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // 형식이 틀린 헤더는 무시한다
            }
        }
        return KO;
    }

    private static String match(String range) {
        String lower = range.toLowerCase(Locale.ROOT);
        if (lower.startsWith("zh")) {
            return "zh-CN";
        }
        for (String lang : ALL) {
            if (lower.equals(lang) || lower.startsWith(lang + "-")) {
                return lang;
            }
        }
        return null;
    }

    /** 있는 언어판 중 보여줄 것: 요청 → en → ko. 어느 것도 없으면 null. */
    public static String pick(Collection<String> available, String requested) {
        for (String lang : List.of(requested, EN, KO)) {
            if (available.contains(lang)) {
                return lang;
            }
        }
        return null;
    }
}
