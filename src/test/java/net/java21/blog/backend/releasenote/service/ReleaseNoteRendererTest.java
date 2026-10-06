package net.java21.blog.backend.releasenote.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.java21.blog.backend.content.HtmlSanitizerPolicy;
import net.java21.blog.backend.content.VideoEmbedTransformer;
import net.java21.blog.backend.releasenote.domain.TocEntry;
import org.junit.jupiter.api.Test;

/**
 * 릴리스 노트 변환(003 T104, research P11): h2~h4 앵커 규칙과 겹침 처리, 목차, 글 본문과 같은 살균, 앵커 형식이 아닌 {@code id} 제거.
 */
class ReleaseNoteRendererTest {

    private final ReleaseNoteRenderer renderer = new ReleaseNoteRenderer(new HtmlSanitizerPolicy(),
            new VideoEmbedTransformer());

    @Test
    void headingsGetAnchorsAndToc() {
        RenderedReleaseNote out = renderer.render("""
                # 큰 제목

                ## 새 기능 (New!)

                ### `API` 변경

                #### 日本語 タイトル

                ##### 작은 제목

                ## 새 기능 (New!)

                ## 새 기능 New
                """);

        assertThat(out.html()).contains("<h1>큰 제목</h1>", "<h2 id=\"새-기능-new\">새 기능 (New!)</h2>",
                "<h3 id=\"api-변경\"><code>API</code> 변경</h3>", "<h4 id=\"日本語-タイトル\">日本語 タイトル</h4>",
                "<h5>작은 제목</h5>", "<h2 id=\"새-기능-new-2\">", "<h2 id=\"새-기능-new-3\">");
        assertThat(out.toc()).containsExactly(
                new TocEntry(2, "새 기능 (New!)", "새-기능-new"),
                new TocEntry(3, "API 변경", "api-변경"),
                new TocEntry(4, "日本語 タイトル", "日本語-タイトル"),
                new TocEntry(2, "새 기능 (New!)", "새-기능-new-2"),
                new TocEntry(2, "새 기능 New", "새-기능-new-3"));
        assertThat(out.text()).contains("큰 제목", "새 기능 (New!)", "작은 제목");
    }

    @Test
    void anchorRules() {
        assertThat(ReleaseNoteRenderer.anchor("Hello  World")).isEqualTo("hello-world");
        assertThat(ReleaseNoteRenderer.anchor("a & b")).isEqualTo("a--b");
        assertThat(ReleaseNoteRenderer.anchor("v1.2.0 — 수정")).isEqualTo("v120--수정");
        assertThat(ReleaseNoteRenderer.anchor("中文 标题")).isEqualTo("中文-标题");
        assertThat(ReleaseNoteRenderer.anchor("!!!")).isEqualTo("section");
        assertThat(ReleaseNoteRenderer.anchor("가".repeat(300))).hasSize(180);
    }

    @Test
    void sanitizedLikePostBodies() {
        RenderedReleaseNote out = renderer.render("""
                ## 제목

                <script>alert(1)</script>

                <a href="javascript:alert(1)" onclick="x()">링크</a>

                <img src="https://example.com/a.png" onerror="x()">

                https://www.youtube.com/watch?v=dQw4w9WgXcQ

                <iframe src="https://evil.example.com/embed"></iframe>
                """);

        assertThat(out.html()).doesNotContain("<script", "alert", "javascript:", "onclick", "onerror", "evil.example")
                .contains("<iframe", "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ")
                .contains("<h2 id=\"제목\">제목</h2>");
    }

    @Test
    void idsOutsideAnchorRulesAreRemoved() {
        RenderedReleaseNote out = renderer.render("""
                <h2 id="Bad Id">대문자</h2>

                <h3 id="ok-anchor">좋음</h3>

                <p id="para">문단</p>

                <h5 id="small">작음</h5>
                """);

        assertThat(out.html()).contains("<h2>대문자</h2>", "<h3 id=\"ok-anchor\">좋음</h3>", "<p>문단</p>",
                "<h5>작음</h5>");
        assertThat(out.toc()).isEmpty();
    }

    @Test
    void blankMarkdownIsEmpty() {
        assertThat(renderer.render("  ")).isEqualTo(RenderedReleaseNote.EMPTY);
        assertThat(renderer.render(null)).isEqualTo(RenderedReleaseNote.EMPTY);
    }
}
