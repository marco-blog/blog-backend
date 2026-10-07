package net.java21.blog.backend.trackback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.support.WebMvcTestSupport;
import net.java21.blog.backend.trackback.controller.TrackbackXmlController;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.repository.TrackbackRepository;
import net.java21.blog.backend.trackback.service.TrackbackReceiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수신 상호 운용(005 T086, SC-009, research M17): 주요 블로그 서비스·설치형 블로그가 보내는 실제 요청 모양 20가지가 컨트롤러 → 실제
 * {@link TrackbackReceiveService}를 거쳐 모두 저장되고, 저장 값이 기대와 같다. 기준은 95%(19/20) 이상이며 지금 목록은 모두 저장되어야 한다.
 */
@WebMvcTest(TrackbackXmlController.class)
@Import({WebMvcTestSupport.class, TrackbackInteropTest.Config.class})
class TrackbackInteropTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    private static final Charset SJIS = Charset.forName("Shift_JIS");
    private static final String UTF8_FORM = "application/x-www-form-urlencoded; charset=utf-8";

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        TrackbackReceiveService trackbackReceiveService(PostRepository postRepository,
                TrackbackRepository trackbackRepository, RateLimitPolicy rateLimits) {
            return new TrackbackReceiveService(postRepository, trackbackRepository, rateLimits,
                    new TrackbackUrls(new SiteProperties("https://blog.java21.net")),
                    new TransactionTemplate(mock(PlatformTransactionManager.class)));
        }
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private PostRepository postRepository;
    @MockitoBean
    private TrackbackRepository trackbackRepository;
    @MockitoBean
    private RateLimitPolicy rateLimits;

    private final List<Trackback> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Blog blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        Post post = TestEntities.post(42L, blog, "받는 글");
        post.publish("받는 글", "본문", "<p>본문</p>", "본문", "요약", null, PostVisibility.PUBLIC, true,
                Instant.parse("2026-10-06T00:00:00Z"));
        when(postRepository.findWithBlogAndOwner(42L)).thenReturn(Optional.of(post));
        when(rateLimits.tryAcquire(any(), anyString())).thenReturn(true);
        when(trackbackRepository.existsByPostIdAndSourceUrlHash(anyLong(), anyString())).thenReturn(false);
        when(trackbackRepository.saveAndFlush(any(Trackback.class))).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
    }

    /** 요청 하나와 저장 기대값. {@code query}는 URL 쿼리 문자열(없으면 null). */
    record Fixture(String name, String contentType, byte[] body, String query, String url, String title,
            String excerpt, String blogName) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static String enc(String value, Charset charset) {
        return URLEncoder.encode(value, charset);
    }

    private static byte[] form(Charset charset, String... pairs) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(pairs[i]).append('=').append(enc(pairs[i + 1], charset));
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    static Stream<Arguments> fixtures() {
        Charset u = StandardCharsets.UTF_8;
        String big = "긴 요약 ".repeat(800);
        return Stream.of(
                new Fixture("Movable Type", UTF8_FORM,
                        form(u, "title", "MT Post", "url", "http://mt.example/archives/001.html", "excerpt",
                                "An excerpt", "blog_name", "MT Blog"),
                        null, "http://mt.example/archives/001.html", "MT Post", "An excerpt", "MT Blog"),
                new Fixture("WordPress", "application/x-www-form-urlencoded; charset=UTF-8",
                        form(u, "title", "WordPress&#8217;s post", "url", "https://wp.example/2026/10/hello/",
                                "blog_name", "WP &amp; Co", "excerpt", "[&#8230;] text [&#8230;]"),
                        null, "https://wp.example/2026/10/hello/", "WordPress’s post", "[…] text […]",
                        "WP & Co"),
                new Fixture("Tistory HTML excerpt", UTF8_FORM,
                        form(u, "url", "https://someone.tistory.com/123", "title", "티스토리 글", "excerpt",
                                "<p>본문 <strong>강조</strong><br/>다음 줄</p>", "blog_name", "티스토리 블로그"),
                        null, "https://someone.tistory.com/123", "티스토리 글", "본문 강조 다음 줄", "티스토리 블로그"),
                new Fixture("EUC-KR install", "application/x-www-form-urlencoded; charset=euc-kr",
                        form(EUC_KR, "url", "http://old.example/zb/view.php?no=7", "title", "제로보드 글", "excerpt",
                                "옛날 요약"),
                        null, "http://old.example/zb/view.php?no=7", "제로보드 글", "옛날 요약", null),
                new Fixture("EUC-KR url only", "application/x-www-form-urlencoded; charset=EUC-KR",
                        form(EUC_KR, "url", "http://old2.example/tb/1"),
                        null, "http://old2.example/tb/1", "http://old2.example/tb/1", null, null),
                new Fixture("No title", UTF8_FORM,
                        form(u, "url", "https://notitle.example/a", "blog_name", "No Title Blog"),
                        null, "https://notitle.example/a", "https://notitle.example/a", null, "No Title Blog"),
                new Fixture("Large excerpt", UTF8_FORM,
                        form(u, "url", "https://big.example/p", "title", "Big", "excerpt", big),
                        null, "https://big.example/p", "Big", big.strip().substring(0, 255).strip(), null),
                new Fixture("HTML entities in title", UTF8_FORM,
                        form(u, "url", "https://ent.example/p", "title", "&lt;b&gt;Hi&lt;/b&gt; &amp; &quot;you&quot;"),
                        null, "https://ent.example/p", "<b>Hi</b> & \"you\"", null, null),
                new Fixture("No charset", "application/x-www-form-urlencoded",
                        form(u, "url", "https://nocs.example/p", "title", "문자셋 없음"),
                        null, "https://nocs.example/p", "문자셋 없음", null, null),
                new Fixture("Quoted charset", "application/x-www-form-urlencoded; charset=\"utf-8\"",
                        form(u, "url", "https://quoted.example/p", "title", "따옴표"),
                        null, "https://quoted.example/p", "따옴표", null, null),
                new Fixture("Plus as space", UTF8_FORM,
                        "url=https%3A%2F%2Fplus.example%2Fp&title=one+two+three".getBytes(StandardCharsets.US_ASCII),
                        null, "https://plus.example/p", "one two three", null, null),
                new Fixture("Emoji title", UTF8_FORM,
                        form(u, "url", "https://emoji.example/p", "title", "축하 🎉 글"),
                        null, "https://emoji.example/p", "축하 🎉 글", null, null),
                new Fixture("Shift_JIS", "application/x-www-form-urlencoded; charset=Shift_JIS",
                        form(SJIS, "url", "http://jp.example/entry/1", "title", "日本語のタイトル", "blog_name", "ブログ"),
                        null, "http://jp.example/entry/1", "日本語のタイトル", null, "ブログ"),
                new Fixture("Query and fragment url", UTF8_FORM,
                        form(u, "url", "https://q.example/view?id=5&lang=ko#comment", "title", "쿼리"),
                        null, "https://q.example/view?id=5&lang=ko#comment", "쿼리", null, null),
                new Fixture("Korean path url", UTF8_FORM,
                        form(u, "url", "https://ko.example/글/한글-주소", "title", "한글 주소"),
                        null, "https://ko.example/글/한글-주소", "한글 주소", null, null),
                new Fixture("Params in query string", "application/x-www-form-urlencoded", new byte[0],
                        "url=http%3A%2F%2Fqs.example%2Fp&title=Query+String", "http://qs.example/p", "Query String",
                        null, null),
                new Fixture("Extra params", UTF8_FORM,
                        form(u, "__mode", "tb", "charset", "utf-8", "url", "https://extra.example/p", "title",
                                "Extra", "tb_id", "42"),
                        null, "https://extra.example/p", "Extra", null, null),
                new Fixture("Repeated param", UTF8_FORM,
                        form(u, "url", "https://rep.example/p", "title", "First", "title", "Second"),
                        null, "https://rep.example/p", "First", null, null),
                new Fixture("Control chars and CRLF", UTF8_FORM,
                        form(u, "url", "https://ctrl.example/p", "title", "Line\u0000One", "excerpt",
                                "first\r\nsecond\tthird\u0007"),
                        null, "https://ctrl.example/p", "Line One", "first second third", null),
                new Fixture("Script in fields", UTF8_FORM,
                        form(u, "url", "https://xss.example/p", "title", "<script>alert(1)</script>Safe title",
                                "excerpt", "<img src=x onerror=alert(1)>text", "blog_name", "<a href='x'>Blog</a>"),
                        null, "https://xss.example/p", "Safe title", "text", "Blog"))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void realWorldPingIsStoredAsExpected(Fixture fixture) throws Exception {
        String path = "/marco/42/trackback" + (fixture.query() == null ? "" : "?" + fixture.query());
        mvc.perform(post(java.net.URI.create(path)).contentType(fixture.contentType()).content(fixture.body()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<error>0</error>")));

        assertThat(saved).singleElement().satisfies(t -> {
            assertThat(t.getSourceUrl()).isEqualTo(fixture.url());
            assertThat(t.getTitle()).isEqualTo(fixture.title());
            assertThat(t.getExcerpt()).isEqualTo(fixture.excerpt());
            assertThat(t.getBlogName()).isEqualTo(fixture.blogName());
            assertThat(t.getSourceUrlHash()).isEqualTo(TrackbackUrls.normalize(fixture.url()).orElseThrow().hash());
        });
    }

    @org.junit.jupiter.api.Test
    void thereAreTwentyFixtures() {
        assertThat(fixtures()).hasSize(20);
    }
}
