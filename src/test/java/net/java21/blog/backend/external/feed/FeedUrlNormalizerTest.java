package net.java21.blog.backend.external.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 007 T008: 피드 주소 정규화와 해시(research E6). */
class FeedUrlNormalizerTest {

    @Test
    void schemeDefaultPortTrailingSlashAndFragmentAreIgnored() {
        String a = FeedUrlNormalizer.hash("HTTPS://Example.COM:443/feed/");
        String b = FeedUrlNormalizer.hash("http://example.com/feed");
        String c = FeedUrlNormalizer.hash("http://example.com/feed#x");
        String d = FeedUrlNormalizer.hash("http://example.com:80/feed//");

        assertThat(a).isEqualTo(b).isEqualTo(c).isEqualTo(d).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(FeedUrlNormalizer.normalize("HTTPS://Example.COM:443/feed/")).isEqualTo("example.com/feed");
    }

    @Test
    void queryIsKeptInOrder() {
        assertThat(FeedUrlNormalizer.normalize("http://example.com/feed?a=1&b=2")).isEqualTo("example.com/feed?a=1&b=2");
        assertThat(FeedUrlNormalizer.hash("http://example.com/feed?a=1&b=2"))
                .isNotEqualTo(FeedUrlNormalizer.hash("http://example.com/feed?b=2&a=1"));
        assertThat(FeedUrlNormalizer.normalize("http://example.com/?")).isEqualTo("example.com");
    }

    @Test
    void idnBecomesPunycode() {
        assertThat(FeedUrlNormalizer.normalize("http://예시.kr/rss")).isEqualTo("xn--vv4b11d.kr/rss");
        assertThat(FeedUrlNormalizer.toUri("예시.kr/rss")).isEqualTo(URI.create("https://xn--vv4b11d.kr/rss"));
    }

    @Test
    void rootPathIsEmptyAndOtherPortsKept() {
        assertThat(FeedUrlNormalizer.normalize("https://example.com/")).isEqualTo("example.com");
        assertThat(FeedUrlNormalizer.normalize("http://example.com:8080/feed")).isEqualTo("example.com:8080/feed");
        assertThat(FeedUrlNormalizer.normalize("http://user:pw@Example.com./x")).isEqualTo("example.com/x");
        assertThat(FeedUrlNormalizer.normalize("http://[::1]:8080/x")).isEqualTo("[::1]:8080/x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "example.com/feed", "http:///feed", "http://example.com:abc/"})
    void invalidUrls(String url) {
        assertThatThrownBy(() -> FeedUrlNormalizer.normalize(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toUriEncodesLooseCharacters() {
        assertThat(FeedUrlNormalizer.toUri("http://example.com/a b?q=가").toString())
                .isEqualTo("http://example.com/a%20b?q=%EA%B0%80");
        assertThat(FeedUrlNormalizer.toUri("http://user@example.com:8080/x").getPort()).isEqualTo(8080);
        assertThatThrownBy(() -> FeedUrlNormalizer.toUri(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedUrlNormalizer.toUri("http://")).isInstanceOf(IllegalArgumentException.class);
        assertThat(FeedUrlNormalizer.sha256("x")).hasSize(64);
    }
}
