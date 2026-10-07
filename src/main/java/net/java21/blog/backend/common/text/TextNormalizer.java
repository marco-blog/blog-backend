package net.java21.blog.backend.common.text;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 비교용 정규화(005 research M11·M12). 저장 값을 바꾸지 않고 금칙어·반복 내용 비교에만 쓴다.
 * <ul>
 *   <li>{@link #nfkcLower}: NFKC → 소문자(전각 "ＢＡＤ" → "bad") → 앞뒤 공백 제거. 금칙어 단어 저장 값.</li>
 *   <li>{@link #compact}: {@code nfkcLower} 뒤 공백·구두점을 모두 지운 값. 이름류 금칙어의 띄어 쓰기 우회 방지.</li>
 *   <li>{@link #contentKey}: {@code nfkcLower} 뒤 연속 공백을 하나로. 반복 내용 비교.</li>
 * </ul>
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String nfkcLower(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }

    public static String compact(String raw) {
        String normalized = nfkcLower(raw);
        StringBuilder out = new StringBuilder(normalized.length());
        normalized.codePoints().filter(cp -> !isSpaceOrPunctuation(cp)).forEach(out::appendCodePoint);
        return out.toString();
    }

    public static String contentKey(String raw) {
        return nfkcLower(raw).replaceAll("\\s+", " ");
    }

    /** 공백 또는 구두점(유니코드 P* 범주). */
    public static boolean isSpaceOrPunctuation(int codePoint) {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }
}
