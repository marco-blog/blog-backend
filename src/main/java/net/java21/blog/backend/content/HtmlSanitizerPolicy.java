package net.java21.blog.backend.content;

import java.util.regex.Pattern;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

/**
 * 글 본문 HTML 살균 정책(FR-021, research R8·R25·R27). 허용 목록에 있는 것만 남긴다.
 * <ul>
 *   <li>태그: Markdown이 만드는 문단·제목·목록·인용·강조·취소선·코드·표·링크·이미지, 그리고 허용된 동영상 iframe.
 *       {@code <script>}·{@code <style>}은 내용까지 지운다.</li>
 *   <li>속성: {@code on*}·{@code style}은 허용 목록에 없어 모두 지운다. 주소는 http·https·mailto와 상대 주소만
 *       ({@code javascript:} 제거). 링크에는 {@code rel="nofollow noopener noreferrer"}를 붙인다.</li>
 *   <li>{@code code}의 class는 {@code language-[a-z0-9+#-]+} 하나만(문법 강조는 front, R24).</li>
 *   <li>{@code iframe}은 src가 YouTube·Vimeo 재생기 주소일 때만 남기고(R25), 속성은
 *       {@code src, width, height, allowfullscreen, loading, title, referrerpolicy}만. 그 밖의 iframe은 지운다.</li>
 * </ul>
 */
@Component
public class HtmlSanitizerPolicy {

    static final Pattern IFRAME_SRC = Pattern.compile(
            "^https://www\\.youtube(-nocookie)?\\.com/embed/[A-Za-z0-9_-]{11}(\\?[^\"]*)?$"
                    + "|^https://player\\.vimeo\\.com/video/\\d+(\\?[^\"]*)?$");
    private static final Pattern LANGUAGE_CLASS = Pattern.compile("language-[a-z0-9+#-]+");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,4}");
    private static final Pattern ALIGN = Pattern.compile("left|center|right");
    private static final Pattern LOADING = Pattern.compile("lazy|eager");
    private static final Pattern REFERRER_POLICY = Pattern.compile(
            "no-referrer|origin|strict-origin|strict-origin-when-cross-origin|same-origin");
    private static final Pattern ANY = Pattern.compile(".*", Pattern.DOTALL);

    private final PolicyFactory policy = new HtmlPolicyBuilder()
            .allowElements("p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
                    "strong", "em", "b", "i", "del", "s", "blockquote", "ul", "ol", "li",
                    "code", "pre", "table", "thead", "tbody", "tr", "th", "td", "a", "img")
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("href", "title").onElements("a")
            .requireRelsOnLinks("nofollow", "noopener", "noreferrer")
            .allowAttributes("src", "alt", "title").onElements("img")
            .allowAttributes("width", "height").matching(NUMBER).onElements("img")
            .allowAttributes("start").matching(NUMBER).onElements("ol")
            .allowAttributes("align").matching(ALIGN).onElements("th", "td")
            .allowAttributes("class").matching(LANGUAGE_CLASS).onElements("code")
            // iframe: 허용 주소가 아닌 src는 속성 정책이 지우고, src가 남지 않은 iframe은 요소 정책이 지운다.
            .allowAttributes("src").matching(IFRAME_SRC).onElements("iframe")
            .allowAttributes("width", "height").matching(NUMBER).onElements("iframe")
            .allowAttributes("loading").matching(LOADING).onElements("iframe")
            .allowAttributes("referrerpolicy").matching(REFERRER_POLICY).onElements("iframe")
            .allowAttributes("title", "allowfullscreen").matching(ANY).onElements("iframe")
            .allowElements((elementName, attrs) -> attrs.contains("src") ? elementName : null, "iframe")
            .toFactory();

    public String sanitize(String html) {
        return policy.sanitize(html);
    }
}
