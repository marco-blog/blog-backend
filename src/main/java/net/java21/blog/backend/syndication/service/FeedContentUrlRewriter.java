package net.java21.blog.backend.syndication.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.java21.blog.backend.config.SiteProperties;
import org.springframework.stereotype.Component;

/**
 * 피드 본문 HTML의 루트 상대 주소({@code src="/media/..."}, {@code href="/marco/12"})를 {@code blog.base-url} 기준 절대 주소로 바꾼다
 * (002 research D6: 리더는 피드 주소의 출처를 모른다). 태그 안의 {@code src}·{@code href} 속성만 보며, 이미 절대 주소이거나
 * {@code //}(프로토콜 상대)·{@code #}·{@code mailto:} 등은 그대로 둔다. 본문은 001에서 저장할 때 이미 살균했다.
 */
@Component
public class FeedContentUrlRewriter {

    private static final Pattern TAG = Pattern.compile("<[A-Za-z][^>]*>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "(\\s(?:src|href)\\s*=\\s*)([\"'])(/(?!/)[^\"']*)\\2", Pattern.CASE_INSENSITIVE);

    private final SiteProperties site;

    public FeedContentUrlRewriter(SiteProperties site) {
        this.site = site;
    }

    public String rewrite(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        Matcher tags = TAG.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 64);
        while (tags.find()) {
            Matcher attributes = ATTRIBUTE.matcher(tags.group());
            String rewritten = attributes.replaceAll(m -> Matcher.quoteReplacement(
                    m.group(1) + m.group(2) + site.url(m.group(3)) + m.group(2)));
            tags.appendReplacement(out, Matcher.quoteReplacement(rewritten));
        }
        tags.appendTail(out);
        return out.toString();
    }
}
