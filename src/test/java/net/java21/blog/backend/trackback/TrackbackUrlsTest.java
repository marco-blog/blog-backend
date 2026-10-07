package net.java21.blog.backend.trackback;

import static org.assertj.core.api.Assertions.assertThat;

import net.java21.blog.backend.config.SiteProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 트랙백 주소 정규화·해시·서비스 안 주소 판별(005 T083, research M13·M15). */
class TrackbackUrlsTest {

    private final TrackbackUrls urls = new TrackbackUrls(new SiteProperties("https://blog.java21.net"));

    @Test
    void normalizesSchemeHostDefaultPortAndFragmentButKeepsPathQueryAndTrailingSlash() {
        assertThat(TrackbackUrls.normalize("  HTTPS://Example.COM:443/Path/To/?a=B&c=d#frag  ")).hasValueSatisfying(n -> {
            assertThat(n.original()).isEqualTo("HTTPS://Example.COM:443/Path/To/?a=B&c=d#frag");
            assertThat(n.normalized()).isEqualTo("https://example.com/Path/To/?a=B&c=d");
            assertThat(n.hash()).hasSize(64).matches("[0-9a-f]{64}");
        });
        assertThat(TrackbackUrls.normalize("http://example.com:80").orElseThrow().normalized())
                .isEqualTo("http://example.com/");
        assertThat(TrackbackUrls.normalize("http://example.com:8080/x").orElseThrow().normalized())
                .isEqualTo("http://example.com:8080/x");
        assertThat(TrackbackUrls.normalize("https://example.com:80/x").orElseThrow().normalized())
                .as("다른 스킴의 기본 포트는 남긴다").isEqualTo("https://example.com:80/x");
        assertThat(TrackbackUrls.normalize("https://example.com/x").orElseThrow().normalized())
                .as("끝 / 유무는 다른 주소").isNotEqualTo(TrackbackUrls.normalize("https://example.com/x/")
                        .orElseThrow().normalized());
    }

    @Test
    void differentSpellingsOfTheSameAddressHashTheSame() {
        String a = TrackbackUrls.normalize("http://EXAMPLE.com:80/글/1#top").orElseThrow().hash();
        String b = TrackbackUrls.normalize("http://example.com/글/1").orElseThrow().hash();
        assertThat(a).isEqualTo(b).isEqualTo(TrackbackUrls.hash("http://example.com/글/1"));
        assertThat(TrackbackUrls.normalize("http://example.com/글/2").orElseThrow().hash()).isNotEqualTo(a);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ftp://example.com/a", "javascript:alert(1)", "mailto:a@b.c", "/relative/path",
            "http://", "http://user:pw@example.com/", "http://exa mple.com/", "http:example.com"})
    void rejectsAnythingButAbsoluteHttpUrls(String raw) {
        assertThat(TrackbackUrls.normalize(raw)).isEmpty();
    }

    @Test
    void rejectsNullAndUrlsLongerThan1000Characters() {
        assertThat(TrackbackUrls.normalize(null)).isEmpty();
        String base = "https://example.com/";
        assertThat(TrackbackUrls.normalize(base + "a".repeat(1000 - base.length()))).isPresent();
        assertThat(TrackbackUrls.normalize(base + "a".repeat(1001 - base.length()))).isEmpty();
    }

    @Test
    void recognizesServicePostAndTrackbackAddresses() {
        assertThat(urls.internalTarget("https://blog.java21.net/marco/42")).hasValueSatisfying(t -> {
            assertThat(t.handle()).isEqualTo("marco");
            assertThat(t.postId()).isEqualTo(42L);
        });
        assertThat(urls.internalTarget("https://BLOG.java21.net:443/marco/42/trackback")).isPresent();
        assertThat(urls.internalTarget("https://blog.java21.net/marco/42/")).isPresent();
        assertThat(urls.internalTarget("https://blog.java21.net/marco/42?x=1#c")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://other.net/marco/42", "https://blog.java21.net:8443/marco/42",
            "http://blog.java21.net/marco/42", "https://blog.java21.net/marco/abc", "https://blog.java21.net/marco",
            "https://blog.java21.net/marco/42/comments", "not a url"})
    void otherHostsPortsAndPathsAreExternal(String url) {
        assertThat(urls.internalTarget(url)).isEmpty();
    }

    @Test
    void baseUrlWithPathPrefixIsHonoured() {
        TrackbackUrls prefixed = new TrackbackUrls(new SiteProperties("http://localhost:8080/blog/"));
        assertThat(prefixed.internalTarget("http://localhost:8080/blog/marco/7/trackback")).isPresent();
        assertThat(prefixed.internalTarget("http://localhost:8080/marco/7")).isEmpty();
        assertThat(prefixed.postUrl("marco", 7)).isEqualTo("http://localhost:8080/blog/marco/7");
    }

    @Test
    void buildsPostAndTrackbackUrls() {
        assertThat(urls.postUrl("marco", 42)).isEqualTo("https://blog.java21.net/marco/42");
        assertThat(urls.trackbackUrl("marco", 42)).isEqualTo("https://blog.java21.net/marco/42/trackback");
    }

    @Test
    void xmlAndTextHelpers() {
        assertThat(TrackbackXml.success()).isEqualTo(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<response><error>0</error></response>");
        assertThat(TrackbackXml.error("a<b>&\"'")).endsWith(
                "<response><error>1</error><message>a&lt;b&gt;&amp;&quot;&apos;</message></response>");
        assertThat(TrackbackXml.escape(null)).isEmpty();
        assertThat(TrackbackText.plain("<p>Hello&nbsp;<b>W&amp;rld</b></p><script>alert(1)</script>"
                + "<!-- c --><style>p{}</style>\u0007end", 255)).isEqualTo("Hello W&rld end");
        assertThat(TrackbackText.plain("&lt;script&gt;x", 255)).isEqualTo("<script>x");
        assertThat(TrackbackText.plain("  <br/>  ", 255)).isNull();
        assertThat(TrackbackText.plain(null, 255)).isNull();
        assertThat(TrackbackText.plain("가".repeat(300), 255)).hasSize(255);
        assertThat(TrackbackText.truncate("a".repeat(254) + "😀", 255)).isEqualTo("a".repeat(254));
    }
}
