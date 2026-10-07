package net.java21.blog.backend.trackback;

/**
 * TrackBack 1.2 응답 본문(005 contracts/api.md "트랙백 받기"). 항상 HTTP 200과 함께 보내며 성공·실패는 {@code <error>} 값으로 알린다.
 * 메시지는 XML로 이스케이프한다.
 */
public final class TrackbackXml {

    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

    private TrackbackXml() {
    }

    public static String success() {
        return HEADER + "<response><error>0</error></response>";
    }

    public static String error(String message) {
        return HEADER + "<response><error>1</error><message>" + escape(message) + "</message></response>";
    }

    /** XML 문자 데이터·속성 값용 이스케이프({@code & < > " '}). null은 빈 문자열. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
