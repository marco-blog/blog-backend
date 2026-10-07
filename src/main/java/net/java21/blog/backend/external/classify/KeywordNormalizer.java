package net.java21.blog.backend.external.classify;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import net.java21.blog.backend.common.text.TextNormalizer;

/**
 * 키워드·피드 태그·매핑 규칙 정규화(007 research E9·E10): NFKC → 소문자(Locale.ROOT) → 앞뒤 공백 제거, 안쪽 연속 공백은 하나로.
 * 영어·숫자만으로 된 낱말은 토큰 완전 일치로, 그 밖(한글·가나·한자·공백·기호가 섞인 낱말)은 부분 문자열로 찾는다.
 */
public final class KeywordNormalizer {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+");

    private KeywordNormalizer() {
    }

    public static String normalize(String raw) {
        return TextNormalizer.nfkcLower(raw).replaceAll("\\s+", " ");
    }

    /** 영어·숫자만으로 된 낱말이면 토큰 완전 일치로 찾는다. */
    public static boolean isTokenKeyword(String normalized) {
        return ASCII_WORD.matcher(normalized).matches();
    }

    /** 정규화한 텍스트를 글자·숫자가 아닌 문자로 잘라 만든 토큰. */
    public static Set<String> tokens(String normalized) {
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        normalized.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) {
                current.appendCodePoint(cp);
            } else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        });
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
