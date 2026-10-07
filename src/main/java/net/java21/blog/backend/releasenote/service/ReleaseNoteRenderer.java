package net.java21.blog.backend.releasenote.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.content.HtmlSanitizerPolicy;
import net.java21.blog.backend.content.MarkdownRenderer;
import net.java21.blog.backend.content.VideoEmbedTransformer;
import net.java21.blog.backend.releasenote.domain.TocEntry;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * 릴리스 노트 Markdown 변환(003 research P11, 001 contracts "ReleaseNoteDetail"). 글 본문과 같은 과정(commonmark GFM → 동영상 줄
 * 변환 → OWASP 살균)에 제목(h2~h4) 앵커와 목차만 더한다.
 * <ul>
 *   <li>앵커: 제목 텍스트를 소문자로 바꾸고 공백을 {@code -}로 바꾼 뒤 문자·숫자·{@code -} 외의 기호를 지운다(한글·한자·가나 유지).
 *       같은 노트 안에서 겹치면 {@code -2}, {@code -3}을 붙인다. 남는 글자가 없으면 {@code section}.</li>
 *   <li>살균은 {@link HtmlSanitizerPolicy#sanitizeReleaseNote}: 앵커 형식의 {@code id}만 h2~h4에 남는다.</li>
 * </ul>
 */
@Component
public class ReleaseNoteRenderer {

    static final String FALLBACK_ANCHOR = "section";

    private final Parser parser;
    private final List<Extension> extensions;
    private final HtmlSanitizerPolicy sanitizerPolicy;
    private final VideoEmbedTransformer videoEmbedTransformer;

    public ReleaseNoteRenderer(HtmlSanitizerPolicy sanitizerPolicy, VideoEmbedTransformer videoEmbedTransformer) {
        this.extensions = List.of(TablesExtension.create(), StrikethroughExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.sanitizerPolicy = sanitizerPolicy;
        this.videoEmbedTransformer = videoEmbedTransformer;
    }

    public RenderedReleaseNote render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return RenderedReleaseNote.EMPTY;
        }
        Node document = parser.parse(markdown);
        videoEmbedTransformer.transform(document);
        Map<Heading, String> anchors = new HashMap<>();
        List<TocEntry> toc = new ArrayList<>();
        Set<String> used = new HashSet<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                if (heading.getLevel() >= 2 && heading.getLevel() <= 4) {
                    String text = textOf(heading).strip().replaceAll("\\s+", " ");
                    String anchor = unique(anchor(text), used);
                    anchors.put(heading, anchor);
                    toc.add(new TocEntry(heading.getLevel(), text, anchor));
                }
            }
        });
        HtmlRenderer htmlRenderer = HtmlRenderer.builder().extensions(extensions)
                .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                    if (node instanceof Heading heading && anchors.containsKey(heading)) {
                        attributes.put("id", anchors.get(heading));
                    }
                })
                .build();
        String html = sanitizerPolicy.sanitizeReleaseNote(htmlRenderer.render(document));
        return new RenderedReleaseNote(html, MarkdownRenderer.textOf(html), List.copyOf(toc));
    }

    /** 제목 텍스트 → 앵커(겹침 처리 전). */
    static String anchor(String text) {
        String lower = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).strip();
        String dashed = lower.replaceAll("\\s+", "-");
        String cleaned = dashed.replaceAll("[^\\p{L}\\p{M}\\p{N}-]", "");
        if (cleaned.length() > 180) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, Math.min(180,
                    cleaned.codePointCount(0, cleaned.length()))));
        }
        return cleaned.isEmpty() ? FALLBACK_ANCHOR : cleaned;
    }

    private static String unique(String anchor, Set<String> used) {
        String candidate = anchor;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = anchor + "-" + n++;
        }
        return candidate;
    }

    private static String textOf(Node parent) {
        StringBuilder sb = new StringBuilder();
        parent.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                sb.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                sb.append(code.getLiteral());
            }
        });
        return sb.toString();
    }
}
