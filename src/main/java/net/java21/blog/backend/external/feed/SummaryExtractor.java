package net.java21.blog.backend.external.feed;

import java.text.BreakIterator;
import java.util.Locale;

/**
 * 요약·제목 자르기(007 research E4). 코드 포인트로 세고, 이모지·결합 문자 같은 글자 묶음 가운데에서 자르지 않는다.
 */
public final class SummaryExtractor {

    /** 요약 최대 코드 포인트. */
    public static final int SUMMARY_MAX = 200;
    /** 제목 최대 코드 포인트. */
    public static final int TITLE_MAX = 300;
    private static final String ELLIPSIS = "…";

    private SummaryExtractor() {
    }

    /** HTML → 태그를 지운 텍스트 → 200자(넘으면 199자 + "…"). 비면 null. */
    public static String summaryOf(String html) {
        String text = HtmlScanner.text(html);
        return text.isEmpty() ? null : ellipsize(text, SUMMARY_MAX);
    }

    /** 텍스트를 {@code max} 코드 포인트 안으로(넘으면 {@code max - 1}자 + "…"). */
    public static String ellipsize(String text, int max) {
        if (text == null) {
            return null;
        }
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return truncate(text, max - 1).stripTrailing() + ELLIPSIS;
    }

    /** {@code max} 코드 포인트 이하로 자른다(말줄임 없음). 글자 묶음 경계에서 자른다. */
    public static String truncate(String text, int max) {
        if (text == null || text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        int limit = text.offsetByCodePoints(0, max);
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(text);
        int cut = graphemes.isBoundary(limit) ? limit : graphemes.preceding(limit);
        if (cut == BreakIterator.DONE || cut <= 0) {
            cut = limit;
        }
        // 그래도 결합 문자·ZWJ·이모지 수식 문자 앞에서 끊겼으면 묶음 시작까지 물린다.
        while (cut > 0 && cut < text.length() && isExtending(text.codePointAt(cut))) {
            cut = text.offsetByCodePoints(cut, -1);
        }
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut);
    }

    private static boolean isExtending(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK || cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0x1F3FB && cp <= 0x1F3FF) || (cp >= 0xE0020 && cp <= 0xE007F);
    }
}
