package net.java21.blog.backend.content;

import java.util.List;
import java.util.Set;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlSanitizer;
import org.springframework.stereotype.Component;

/**
 * 글 본문 Markdown을 저장 시 한 번 HTML로 바꾸고 살균한다(FR-021, research R8).
 * commonmark-java(GFM 표·취소선) → 동영상 줄 변환({@link VideoEmbedTransformer}) → OWASP 살균({@link HtmlSanitizerPolicy}).
 * 조회 때는 저장된 HTML을 그대로 쓴다. 본문에 직접 쓴 HTML도 같은 살균을 거친다.
 */
@Component
public class MarkdownRenderer {

    /** 텍스트 추출 때 앞뒤를 띄어 쓰는 블록 요소. */
    private static final Set<String> BLOCKS = Set.of("p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "blockquote", "ul", "ol", "li", "pre", "table", "thead", "tbody", "tr", "th", "td", "iframe");

    private final Parser parser;
    private final HtmlRenderer htmlRenderer;
    private final HtmlSanitizerPolicy sanitizerPolicy;
    private final VideoEmbedTransformer videoEmbedTransformer;

    public MarkdownRenderer(HtmlSanitizerPolicy sanitizerPolicy, VideoEmbedTransformer videoEmbedTransformer) {
        List<Extension> extensions = List.of(TablesExtension.create(), StrikethroughExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.htmlRenderer = HtmlRenderer.builder().extensions(extensions).build();
        this.sanitizerPolicy = sanitizerPolicy;
        this.videoEmbedTransformer = videoEmbedTransformer;
    }

    public RenderedContent render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return RenderedContent.EMPTY;
        }
        Node document = parser.parse(markdown);
        videoEmbedTransformer.transform(document);
        String html = sanitizerPolicy.sanitize(htmlRenderer.render(document));
        return RenderedContent.of(html, textOf(html));
    }

    /** 살균된 HTML에서 태그를 빼고(엔티티는 풀어서) 공백을 한 칸으로 줄인 텍스트. */
    static String textOf(String safeHtml) {
        StringBuilder sb = new StringBuilder();
        HtmlSanitizer.sanitize(safeHtml, new HtmlSanitizer.Policy() {
            @Override
            public void openDocument() {
            }

            @Override
            public void closeDocument() {
            }

            @Override
            public void openTag(String elementName, List<String> attrs) {
                if (BLOCKS.contains(elementName)) {
                    sb.append(' ');
                }
            }

            @Override
            public void closeTag(String elementName) {
                if (BLOCKS.contains(elementName)) {
                    sb.append(' ');
                }
            }

            @Override
            public void text(String textChunk) {
                sb.append(textChunk);
            }
        });
        return sb.toString().replaceAll("\\s+", " ").strip();
    }
}
