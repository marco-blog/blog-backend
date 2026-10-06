package net.java21.blog.backend.content;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 변환 결과(research R8).
 *
 * @param html    살균된 HTML({@code posts.content_html})
 * @param text    태그를 뺀 텍스트, 공백은 한 칸으로({@code posts.content_text}, 검색용)
 * @param summary {@code text}의 앞 {@value #SUMMARY_LENGTH}자({@code posts.summary}, 메타 description)
 */
public record RenderedContent(String html, String text, String summary) {

    public static final int SUMMARY_LENGTH = 150;

    public static final RenderedContent EMPTY = new RenderedContent("", "", "");

    /** 본문에 올린 이미지 주소 {@code /media/{media_key}}(22자 base62, FR-156). */
    private static final Pattern MEDIA_IMAGE = Pattern.compile("<img\\b[^>]*\\bsrc=\"(/media/([A-Za-z0-9]{22}))\"");

    static RenderedContent of(String html, String text) {
        return new RenderedContent(html, text, summarize(text));
    }

    /** 본문의 첫 업로드 이미지 주소. 대표 이미지를 고르지 않았을 때의 기본값(FR-107). 없으면 null. */
    public String firstMediaImageUrl() {
        Matcher matcher = MEDIA_IMAGE.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 본문에 이 키의 업로드 이미지가 있는지(대표 이미지는 본문 이미지 중 하나여야 한다). */
    public boolean containsMediaImage(String mediaKey) {
        Matcher matcher = MEDIA_IMAGE.matcher(html);
        while (matcher.find()) {
            if (matcher.group(2).equals(mediaKey)) {
                return true;
            }
        }
        return false;
    }

    private static String summarize(String text) {
        if (text.codePointCount(0, text.length()) <= SUMMARY_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, SUMMARY_LENGTH));
    }
}
