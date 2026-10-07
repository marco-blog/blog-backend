package net.java21.blog.backend.external.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

/** 007 T010: HTML 훑기(research E3·E4). */
class HtmlScannerTest {

    private static final URI BASE = URI.create("https://blog.example/posts/1");

    @Test
    void feedLinksInDocumentOrder() {
        String html = """
                <html><head>
                <link rel="stylesheet" href="/a.css" type="text/css">
                <link REL="Alternate home" type="application/atom+xml" href="/atom.xml">
                <link rel="alternate" type="application/rss+xml; charset=utf-8" href="https://blog.example/rss">
                <link rel="alternate" type="text/html" href="/en">
                <link rel="alternate" type="application/rss+xml">
                </head><body></body></html>
                """;

        HtmlScanner.Scan scan = HtmlScanner.scan(html, BASE);

        assertThat(scan.feedLinks()).extracting(HtmlScanner.FeedLink::href)
                .containsExactly(URI.create("https://blog.example/atom.xml"), URI.create("https://blog.example/rss"));
        assertThat(scan.feedLinks()).extracting(HtmlScanner.FeedLink::type)
                .containsExactly("application/atom+xml", "application/rss+xml");
    }

    @Test
    void textDropsScriptAndStyleAndDecodesEntities() {
        String html = "<style>p{color:red}</style><p>Hello&nbsp;<b>world</b> &amp; you</p><div>next&#x1F600;</div>"
                + "<script>alert(1)</script><noscript>no</noscript>  tail";

        assertThat(HtmlScanner.text(html)).isEqualTo("Hello world & you next😀 tail");
    }

    @Test
    void dangerousMarkupNeverSurvivesAsMarkup() {
        String text = HtmlScanner.text("<img src=x onerror=alert(1)><script>alert(1)</script>safe");

        assertThat(text).isEqualTo("safe").doesNotContain("<");
    }

    @Test
    void metaValuesAndImages() {
        HtmlScanner.Scan scan = HtmlScanner.scan("""
                <meta name="java21-verify" content="java21-verify-ABC"><meta property="x" value="v">
                <img src="/a.png" width="10px" height="20"><img src="b.png" width="auto">
                """, BASE);

        assertThat(scan.metaValues()).containsExactly("java21-verify-ABC", "v");
        assertThat(scan.images()).extracting(HtmlScanner.Image::src).containsExactly("/a.png", "b.png");
        assertThat(scan.images().getFirst().width()).isEqualTo(10);
        assertThat(scan.images().get(1).width()).isNull();
    }

    @Test
    void emptyAndNull() {
        assertThat(HtmlScanner.text(null)).isEmpty();
        assertThat(HtmlScanner.scan("", BASE).feedLinks()).isEmpty();
        assertThat(HtmlScanner.collapse(null)).isEmpty();
        assertThat(HtmlScanner.resolve(null, "/x")).isNull();
        assertThat(HtmlScanner.resolve(BASE, " ")).isNull();
        assertThat(HtmlScanner.resolve(BASE, "../x")).isEqualTo(URI.create("https://blog.example/x"));
    }
}
