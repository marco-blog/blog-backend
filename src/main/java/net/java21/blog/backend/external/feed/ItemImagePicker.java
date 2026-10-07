package net.java21.blog.backend.external.feed;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import org.jdom2.Element;
import org.jdom2.Namespace;

/**
 * 대표 이미지 고르기(007 research E4): (1) {@code <enclosure type="image/*">} → (2) Media RSS {@code media:thumbnail}·
 * {@code media:content}(rome-modules 없이 foreign markup) → (3) 본문 첫 {@code <img>}(가로·세로 속성이 둘 다 50 미만이면 추적 픽셀로 보고
 * 건너뜀). http/https, 1000자 이하만.
 */
public final class ItemImagePicker {

    static final Namespace MEDIA = Namespace.getNamespace("http://search.yahoo.com/mrss/");
    private static final int MAX_LENGTH = 1000;
    private static final int PIXEL = 50;

    private ItemImagePicker() {
    }

    public static String pick(SyndEntry entry, List<HtmlScanner.Image> bodyImages, URI base) {
        for (SyndEnclosure enclosure : entry.getEnclosures()) {
            String type = enclosure.getType();
            if (type != null && type.toLowerCase(Locale.ROOT).startsWith("image/")) {
                String url = accept(enclosure.getUrl(), base);
                if (url != null) {
                    return url;
                }
            }
        }
        String media = fromMedia(entry.getForeignMarkup(), base);
        if (media != null) {
            return media;
        }
        for (HtmlScanner.Image image : bodyImages) {
            if (image.width() != null && image.height() != null && image.width() < PIXEL && image.height() < PIXEL) {
                continue;
            }
            String url = accept(image.src(), base);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    private static String fromMedia(List<Element> elements, URI base) {
        if (elements == null) {
            return null;
        }
        String content = null;
        for (Element e : elements) {
            if (!MEDIA.getURI().equals(e.getNamespaceURI())) {
                continue;
            }
            if (e.getName().equals("thumbnail")) {
                String url = accept(e.getAttributeValue("url"), base);
                if (url != null) {
                    return url;
                }
            } else if (e.getName().equals("content") && content == null && isImage(e)) {
                content = accept(e.getAttributeValue("url"), base);
            } else if (e.getName().equals("group")) {
                String nested = fromMedia(e.getChildren(), base);
                if (nested != null) {
                    return nested;
                }
            }
            if (e.getName().equals("content")) {
                Element thumb = e.getChild("thumbnail", MEDIA);
                if (thumb != null) {
                    String url = accept(thumb.getAttributeValue("url"), base);
                    if (url != null) {
                        return url;
                    }
                }
            }
        }
        return content;
    }

    private static boolean isImage(Element content) {
        String medium = content.getAttributeValue("medium");
        String type = content.getAttributeValue("type");
        return "image".equalsIgnoreCase(medium) || (type != null && type.toLowerCase(Locale.ROOT).startsWith("image/"));
    }

    /** 절대 http/https 주소이고 1000자 이하면 그 주소. */
    static String accept(String url, URI base) {
        URI resolved = HtmlScanner.resolve(base, url);
        if (resolved == null || resolved.getScheme() == null || resolved.getHost() == null) {
            return null;
        }
        String scheme = resolved.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        String s = resolved.toString();
        return s.length() > MAX_LENGTH ? null : s;
    }
}
