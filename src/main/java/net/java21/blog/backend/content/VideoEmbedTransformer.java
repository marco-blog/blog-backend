package net.java21.blog.backend.content;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.springframework.stereotype.Component;

/**
 * 한 줄에 동영상 주소만 있는 문단을 재생기 iframe으로 바꾼다(FR-140, research R25).
 * YouTube({@code youtube.com/watch?v=}, {@code youtu.be/})는 {@code youtube-nocookie.com/embed}로,
 * Vimeo({@code vimeo.com/{id}})는 {@code player.vimeo.com/video/{id}}로 바꾼다.
 * 문장 안의 주소와 코드 블록은 그대로 둔다. 결과 iframe도 {@link HtmlSanitizerPolicy}를 거친다.
 */
@Component
public class VideoEmbedTransformer {

    private static final Pattern YOUTUBE_WATCH = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?youtube\\.com/watch\\?(?:\\S*&)?v=([A-Za-z0-9_-]{11})(?:[&#]\\S*)?$");
    private static final Pattern YOUTUBE_SHORT = Pattern.compile(
            "^https?://youtu\\.be/([A-Za-z0-9_-]{11})(?:[?#]\\S*)?$");
    private static final Pattern VIMEO = Pattern.compile(
            "^https?://(?:www\\.)?vimeo\\.com/(\\d{1,12})(?:[/?#]\\S*)?$");

    static final String IFRAME_ATTRIBUTES =
            " width=\"560\" height=\"315\" loading=\"lazy\" referrerpolicy=\"strict-origin-when-cross-origin\" allowfullscreen";

    /** 문서 트리에서 동영상 주소만 있는 문단을 iframe HTML 블록으로 바꾼다. */
    public void transform(Node document) {
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Paragraph paragraph) {
                singleUrl(paragraph).flatMap(VideoEmbedTransformer::embedUrl).ifPresent(src -> {
                    HtmlBlock iframe = new HtmlBlock();
                    iframe.setLiteral("<iframe src=\"" + src + "\" title=\"video\"" + IFRAME_ATTRIBUTES + "></iframe>\n");
                    paragraph.insertAfter(iframe);
                    paragraph.unlink();
                });
            }
        });
    }

    /** 문단이 주소 하나(일반 텍스트 또는 {@code <주소>} 자동 링크)뿐이면 그 주소. */
    private static Optional<String> singleUrl(Paragraph paragraph) {
        Node first = paragraph.getFirstChild();
        if (first instanceof Link link && link.getNext() == null) {
            String label = textOf(link);
            return label != null && label.equals(link.getDestination()) ? Optional.of(label) : Optional.empty();
        }
        String text = textOf(paragraph);
        return text == null ? Optional.empty() : Optional.of(text.strip());
    }

    /** 자식이 모두 텍스트일 때 이어 붙인 값, 아니면 null. */
    private static String textOf(Node parent) {
        StringBuilder sb = new StringBuilder();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof Text text)) {
                return null;
            }
            sb.append(text.getLiteral());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    static Optional<String> embedUrl(String url) {
        Matcher matcher = YOUTUBE_WATCH.matcher(url);
        if (matcher.matches()) {
            return Optional.of("https://www.youtube-nocookie.com/embed/" + matcher.group(1));
        }
        matcher = YOUTUBE_SHORT.matcher(url);
        if (matcher.matches()) {
            return Optional.of("https://www.youtube-nocookie.com/embed/" + matcher.group(1));
        }
        matcher = VIMEO.matcher(url);
        if (matcher.matches()) {
            return Optional.of("https://player.vimeo.com/video/" + matcher.group(1));
        }
        return Optional.empty();
    }
}
