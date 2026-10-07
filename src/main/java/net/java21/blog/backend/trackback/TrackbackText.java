package net.java21.blog.backend.trackback;

import java.util.regex.Pattern;

import org.owasp.html.Encoding;

/**
 * 트랙백 제목·요약·블로그 이름 정리(005 research M13): HTML 태그(스크립트·스타일 내용 포함)와 주석을 지우고 엔터티를 풀고 제어 문자를
 * 지운 뒤 공백을 하나로 모아 최대 길이에서 자른다(서로게이트 쌍을 가르지 않음). 화면은 텍스트로만 출력한다.
 */
public final class TrackbackText {

    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("(?s)</?[A-Za-z!/?][^>]*>");
    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");

    private TrackbackText() {
    }

    /** 정리한 일반 텍스트. 비면 null. */
    public static String plain(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String text = withoutControls(raw);
        text = SCRIPT_OR_STYLE.matcher(text).replaceAll(" ");
        text = COMMENT.matcher(text).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        text = withoutControls(Encoding.decodeHtml(text));
        text = SPACES.matcher(text).replaceAll(" ").strip();
        if (text.isEmpty()) {
            return null;
        }
        return truncate(text, max);
    }

    /** 최대 {@code max}자(UTF-16 단위)에서 자르되 서로게이트 쌍을 가르지 않는다. */
    public static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).strip();
    }

    /** 제어 문자(줄바꿈·탭 포함)를 공백으로. */
    private static String withoutControls(String text) {
        StringBuilder cleaned = new StringBuilder(text.length());
        text.codePoints().map(cp -> Character.isISOControl(cp) ? ' ' : cp).forEach(cleaned::appendCodePoint);
        return cleaned.toString();
    }
}
