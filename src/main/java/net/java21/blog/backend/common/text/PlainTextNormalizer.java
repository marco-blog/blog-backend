package net.java21.blog.backend.common.text;

/**
 * 사용자가 쓴 일반 텍스트(댓글·방명록 내용, 비회원 이름) 정리(001 research R8, 004 research B6·B7). HTML은 그대로 두고 front가 출력할 때
 * 이스케이프한다.
 */
public final class PlainTextNormalizer {

    private PlainTextNormalizer() {
    }

    /**
     * 여러 줄 내용: 줄바꿈을 {@code \n}으로 맞추고 줄바꿈·탭이 아닌 제어 문자를 지운 뒤 앞뒤 공백을 없앤다. null은 빈 문자열.
     */
    public static String multiline(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder cleaned = new StringBuilder(text.length());
        text.codePoints()
                .filter(cp -> cp == '\n' || cp == '\t' || !Character.isISOControl(cp))
                .forEach(cleaned::appendCodePoint);
        return cleaned.toString().strip();
    }

    /** 한 줄 값(이름): 모든 제어 문자를 지우고 앞뒤 공백을 없앤다. null은 빈 문자열. */
    public static String singleLine(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        raw.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(cleaned::appendCodePoint);
        return cleaned.toString().strip();
    }
}
