package net.java21.blog.backend.syndication.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.java21.blog.backend.config.SiteProperties;
import org.junit.jupiter.api.Test;

/**
 * 피드 본문의 상대 주소를 절대 주소로(T083, research D6): {@code src="/media/..."}, {@code href="/marco/12"}를 {@code blog.base-url} 기준으로,
 * 이미 절대 주소·{@code //}·{@code #}·{@code mailto:}는 그대로, 속성 따옴표 두 종류.
 */
class FeedContentUrlRewriterTest {

    private final FeedContentUrlRewriter rewriter = new FeedContentUrlRewriter(new SiteProperties("https://blog.java21.net/"));

    @Test
    void rootRelativeSrcAndHrefBecomeAbsolute() {
        String html = "<p><img src=\"/media/k3Jd9fQ2xLmA7pZ0bR5tYw/600x400\" alt=\"x\"> <a href='/marco/12'>다음 글</a></p>";

        assertThat(rewriter.rewrite(html)).isEqualTo(
                "<p><img src=\"https://blog.java21.net/media/k3Jd9fQ2xLmA7pZ0bR5tYw/600x400\" alt=\"x\">"
                        + " <a href='https://blog.java21.net/marco/12'>다음 글</a></p>");
    }

    @Test
    void absoluteProtocolRelativeFragmentAndMailtoStayAsTheyAre() {
        String html = "<a href=\"https://example.com/x\">a</a><img src=\"//cdn.example.com/i.png\">"
                + "<a href=\"#section\">b</a><a href=\"mailto:me@example.com\">c</a><a href=\"relative/x\">d</a>";

        assertThat(rewriter.rewrite(html)).isEqualTo(html);
    }

    @Test
    void attributeNamesAreCaseInsensitiveAndSpacingIsKept() {
        assertThat(rewriter.rewrite("<IMG SRC = \"/media/a\"><a data-href=\"/x\" HREF=\"/\">"))
                .isEqualTo("<IMG SRC = \"https://blog.java21.net/media/a\"><a data-href=\"/x\" HREF=\"https://blog.java21.net/\">");
    }

    @Test
    void textThatLooksLikeAnAttributeIsNotTouched() {
        assertThat(rewriter.rewrite("<p>src=\"/media/a\" 라고 쓰면</p>")).isEqualTo("<p>src=\"/media/a\" 라고 쓰면</p>");
    }

    @Test
    void nullAndEmptyStayEmpty() {
        assertThat(rewriter.rewrite(null)).isNull();
        assertThat(rewriter.rewrite("")).isEmpty();
    }
}
