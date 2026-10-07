package net.java21.blog.backend.external.feed;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.owasp.html.HtmlSanitizer;

/**
 * 외부 HTML을 훑는 도구(007 research E3·E4). OWASP {@link HtmlSanitizer}의 이벤트 수신기로 태그 이름·속성·텍스트만 받는다(출력 HTML을
 * 만들지 않음). 피드 찾기의 {@code <link rel=alternate>}, 대표 이미지의 첫 {@code <img>}, 요약·인증 확인의 텍스트, {@code meta}
 * 속성 값을 모은다. {@code <script>}·{@code <style>} 안 텍스트는 버린다.
 */
public final class HtmlScanner {

    private static final Set<String> BLOCKS = Set.of("p", "div", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5",
            "h6", "tr", "td", "th", "table", "blockquote", "pre", "section", "article", "header", "footer", "hr",
            "figure", "figcaption", "dd", "dt", "dl", "main", "nav", "aside", "img", "title", "option");
    private static final Set<String> SKIP_TEXT = Set.of("script", "style", "noscript", "template", "textarea");
    private static final Set<String> FEED_TYPES = Set.of("application/rss+xml", "application/atom+xml");

    /** {@code <link rel=alternate type=rss|atom>}. */
    public record FeedLink(URI href, String type) {
    }

    /** {@code <img>}. 크기 속성이 숫자가 아니면 null. */
    public record Image(String src, Integer width, Integer height) {
    }

    /**
     * 훑은 결과.
     *
     * @param text       태그를 지운 텍스트(엔터티 풀기, 공백 정리)
     * @param feedLinks  문서 순서의 피드 링크(기준 주소로 푼 절대 주소)
     * @param images     문서 순서의 이미지
     * @param metaValues {@code meta}의 {@code content}·{@code value} 값
     */
    public record Scan(String text, List<FeedLink> feedLinks, List<Image> images, List<String> metaValues) {
    }

    private HtmlScanner() {
    }

    /** 텍스트만. */
    public static String text(String html) {
        return scan(html, null).text();
    }

    /** {@code base}가 null이면 링크를 풀지 않고 절대 주소만 모은다. */
    public static Scan scan(String html, URI base) {
        StringBuilder text = new StringBuilder();
        List<FeedLink> links = new ArrayList<>();
        List<Image> images = new ArrayList<>();
        List<String> metas = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return new Scan("", links, images, metas);
        }
        int[] skipDepth = {0};
        HtmlSanitizer.sanitize(html, new HtmlSanitizer.Policy() {
            @Override
            public void openDocument() {
            }

            @Override
            public void closeDocument() {
            }

            @Override
            public void openTag(String elementName, List<String> attrs) {
                String name = elementName.toLowerCase(Locale.ROOT);
                if (SKIP_TEXT.contains(name)) {
                    skipDepth[0]++;
                }
                if (BLOCKS.contains(name)) {
                    text.append(' ');
                }
                switch (name) {
                    case "link" -> feedLink(attrs, base, links);
                    case "img" -> images.add(new Image(attr(attrs, "src"), number(attr(attrs, "width")),
                            number(attr(attrs, "height"))));
                    case "meta" -> {
                        String content = attr(attrs, "content");
                        if (content != null && !content.isBlank()) {
                            metas.add(content);
                        }
                        String value = attr(attrs, "value");
                        if (value != null && !value.isBlank()) {
                            metas.add(value);
                        }
                    }
                    default -> {
                    }
                }
            }

            @Override
            public void closeTag(String elementName) {
                String name = elementName.toLowerCase(Locale.ROOT);
                if (SKIP_TEXT.contains(name) && skipDepth[0] > 0) {
                    skipDepth[0]--;
                }
                if (BLOCKS.contains(name)) {
                    text.append(' ');
                }
            }

            @Override
            public void text(String textChunk) {
                if (skipDepth[0] == 0) {
                    text.append(textChunk);
                }
            }
        });
        return new Scan(collapse(text.toString()), links, images, metas);
    }

    /** 연속 공백을 한 칸으로, 앞뒤 공백 제거. */
    public static String collapse(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s\\u00A0\\u2028\\u2029]+", " ").strip();
    }

    private static void feedLink(List<String> attrs, URI base, List<FeedLink> links) {
        String rel = attr(attrs, "rel");
        String type = attr(attrs, "type");
        String href = attr(attrs, "href");
        if (rel == null || type == null || href == null || href.isBlank()) {
            return;
        }
        boolean alternate = false;
        for (String r : rel.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (r.equals("alternate")) {
                alternate = true;
            }
        }
        String t = type.toLowerCase(Locale.ROOT).strip();
        int semi = t.indexOf(';');
        if (semi >= 0) {
            t = t.substring(0, semi).strip();
        }
        if (!alternate || !FEED_TYPES.contains(t)) {
            return;
        }
        URI resolved = resolve(base, href);
        if (resolved != null) {
            links.add(new FeedLink(resolved, t));
        }
    }

    /** 상대 주소를 풂. 풀 수 없으면 null. */
    public static URI resolve(URI base, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        try {
            URI ref = URI.create(FeedUrlNormalizer.encodeLoose(href.strip()));
            if (ref.isAbsolute()) {
                return ref;
            }
            return base == null ? null : base.resolve(ref);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String attr(List<String> attrs, String name) {
        for (int i = 0; i + 1 < attrs.size(); i += 2) {
            if (attrs.get(i).equalsIgnoreCase(name)) {
                return attrs.get(i + 1);
            }
        }
        return null;
    }

    private static Integer number(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toLowerCase(Locale.ROOT).replace("px", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
