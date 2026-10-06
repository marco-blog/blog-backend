package net.java21.blog.backend.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Markdown 변환과 살균(T059, FR-021·083·140, research R8·R25, quickstart #6·18).
 * 살균은 저장 시 서버 한 곳에서 하므로 여기서 위험한 입력을 모두 확인한다.
 */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer(new HtmlSanitizerPolicy(), new VideoEmbedTransformer());

    @Test
    void rendersBasicMarkdown() {
        RenderedContent out = renderer.render("# 제목\n\n**굵게** 와 *기울임*, [링크](https://example.com)\n\n- 하나\n- 둘");

        assertThat(out.html()).contains("<h1>제목</h1>", "<strong>굵게</strong>", "<em>기울임</em>", "<li>하나</li>")
                .contains("href=\"https://example.com\"");
    }

    @Test
    void rendersGfmTablesAndStrikethrough() {
        RenderedContent out = renderer.render("| a | b |\n|---|:-:|\n| 1 | 2 |\n\n~~지움~~");

        assertThat(out.html()).contains("<table>", "<thead>", "<th>a</th>", "<tbody>", "<td>1</td>", "<del>지움</del>")
                .contains("align=\"center\"");
    }

    @Test
    void scriptIsRemovedWithItsContent() {
        RenderedContent out = renderer.render("앞\n\n<script>alert('xss')</script>\n\n뒤 <script>steal()</script>");

        assertThat(out.html()).doesNotContainIgnoringCase("<script").doesNotContain("alert", "steal()");
        assertThat(out.html()).contains("앞", "뒤");
        assertThat(out.text()).doesNotContain("alert", "steal");
    }

    @Test
    void eventHandlersJavascriptUrlsAndStylesAreRemoved() {
        RenderedContent out = renderer.render("""
                <img src="/media/k3Jd9fQ2xLmA7pZ0bR5tYw" onerror="alert(1)" alt="그림">

                [클릭](javascript:alert(2)) <a href="JaVaScRiPt:alert(3)">x</a>

                <p style="color:red" onclick="alert(4)">문단</p>

                <div onmouseover="alert(5)"><span style="position:fixed">겹침</span></div>
                """);

        assertThat(out.html()).doesNotContainIgnoringCase("onerror").doesNotContainIgnoringCase("onclick")
                .doesNotContainIgnoringCase("onmouseover").doesNotContainIgnoringCase("javascript:")
                .doesNotContain("style=", "alert");
        assertThat(out.html()).contains("src=\"/media/k3Jd9fQ2xLmA7pZ0bR5tYw\"", "alt=\"그림\"", "문단", "겹침");
    }

    @Test
    void codeBlockKeepsOnlyLanguageClass() {
        RenderedContent out = renderer.render("```java\nclass A { String s = \"<b>\"; }\n```\n\n"
                + "<pre><code class=\"hljs evil language-js\">x</code></pre>\n\n<code class=\"language-c++\">y</code>");

        assertThat(out.html()).contains("<pre><code class=\"language-java\">class A { String s &#61; &#34;&lt;b&gt;&#34;; }")
                .doesNotContain("hljs", "evil")
                .contains("<code class=\"language-c&#43;&#43;\">y</code>"); // 브라우저에서는 language-c++
    }

    @Test
    void codeBlockWithoutLanguageHasNoClass() {
        assertThat(renderer.render("```\nplain\n```").html()).contains("<pre><code>plain");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?t=42",
            "<https://www.youtube.com/watch?v=dQw4w9WgXcQ>"
    })
    void youtubeLineBecomesNoCookieIframe(String line) {
        RenderedContent out = renderer.render("소개\n\n" + line + "\n\n끝");

        assertThat(out.html()).contains("<iframe src=\"https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ\"")
                .contains("allowfullscreen", "loading=\"lazy\"")
                .doesNotContain("watch?v=");
    }

    @Test
    void vimeoLineBecomesPlayerIframe() {
        assertThat(renderer.render("https://vimeo.com/76979871").html())
                .contains("<iframe src=\"https://player.vimeo.com/video/76979871\"");
    }

    @Test
    void videoUrlInsideSentenceOrCodeStaysText() {
        RenderedContent out = renderer.render("보세요 https://youtu.be/dQw4w9WgXcQ 재밌어요\n\n```\nhttps://youtu.be/dQw4w9WgXcQ\n```");

        assertThat(out.html()).doesNotContain("<iframe");
    }

    @Test
    void handWrittenIframesOnlyPassForAllowedPlayers() {
        RenderedContent out = renderer.render("""
                <iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ" width="560" height="315" frameborder="0" style="x" onload="alert(1)" allowfullscreen></iframe>

                <iframe src="https://player.vimeo.com/video/123?h=abc" title="v"></iframe>

                <iframe src="https://evil.example.com/embed/dQw4w9WgXcQ"></iframe>

                <iframe src="https://www.youtube.com/embed/short"></iframe>

                <iframe src="javascript:alert(1)"></iframe>

                <iframe srcdoc="<script>alert(1)</script>"></iframe>
                """);

        assertThat(out.html()).contains("<iframe src=\"https://www.youtube.com/embed/dQw4w9WgXcQ\" width=\"560\" height=\"315\"")
                .contains("<iframe src=\"https://player.vimeo.com/video/123?h&#61;abc\" title=\"v\"")
                .doesNotContain("evil.example.com", "frameborder", "style=", "onload", "srcdoc", "alert", "/embed/short");
        assertThat(out.html().split("<iframe", -1)).hasSize(3);
    }

    @Test
    void textAndSummaryAreTagFreeAndSummaryIs150Chars() {
        String longBody = "가".repeat(200);
        RenderedContent out = renderer.render("# 제목\n\n<b>굵게</b> &amp; 그리고 `코드`\n\n" + longBody);

        assertThat(out.text()).startsWith("제목 굵게 & 그리고 코드 가").doesNotContain("<", ">");
        assertThat(out.summary()).hasSize(RenderedContent.SUMMARY_LENGTH).isEqualTo(out.text().substring(0, 150));
    }

    @Test
    void shortTextIsItsOwnSummary() {
        RenderedContent out = renderer.render("짧은 글");
        assertThat(out.text()).isEqualTo("짧은 글");
        assertThat(out.summary()).isEqualTo("짧은 글");
    }

    @Test
    void summaryDoesNotSplitSurrogatePairs() {
        String emoji = "😀".repeat(200);
        RenderedContent out = renderer.render(emoji);
        assertThat(out.summary().codePointCount(0, out.summary().length())).isEqualTo(150);
    }

    @Test
    void emptyMarkdownRendersEmpty() {
        RenderedContent out = renderer.render(null);
        assertThat(out.html()).isEmpty();
        assertThat(out.text()).isEmpty();
        assertThat(out.summary()).isEmpty();
        assertThat(out.firstMediaImageUrl()).isNull();
    }

    @Test
    void firstMediaImageIsFoundForDefaultThumbnail() {
        RenderedContent out = renderer.render("![외부](https://cdn.example.com/a.png)\n\n![a](/media/k3Jd9fQ2xLmA7pZ0bR5tYw)\n\n"
                + "![b](/media/AAAAAAAAAAAAAAAAAAAAAA)");
        assertThat(out.firstMediaImageUrl()).isEqualTo("/media/k3Jd9fQ2xLmA7pZ0bR5tYw");
        assertThat(out.containsMediaImage("AAAAAAAAAAAAAAAAAAAAAA")).isTrue();
        assertThat(out.containsMediaImage("BBBBBBBBBBBBBBBBBBBBBB")).isFalse();
    }

    @Test
    void linksGetSafeRel() {
        assertThat(renderer.render("[a](https://example.com)").html()).contains("rel=\"nofollow noopener noreferrer\"");
    }

    /** 003 T104: 회원 글 본문은 릴리스 노트와 달리 제목에도 {@code id}를 남기지 않는다. */
    @Test
    void memberPostsNeverKeepIds() {
        RenderedContent out = renderer.render("## 새 기능\n\n<h2 id=\"anchor\">직접</h2>\n\n<p id=\"x\">문단</p>");

        assertThat(out.html()).doesNotContain("id=").contains("<h2>새 기능</h2>", "<h2>직접</h2>");
    }
}
