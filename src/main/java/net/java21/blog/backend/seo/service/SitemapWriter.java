package net.java21.blog.backend.seo.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.springframework.stereotype.Component;

/**
 * 사이트맵 XML 작성(sitemaps.org 0.9, JDK StAX, research D5). {@code changefreq}·{@code priority}는 넣지 않는다.
 * {@code lastmod}는 W3C Datetime(초 단위 UTC, 예: {@code 2026-10-06T04:24:19Z}).
 */
@Component
public class SitemapWriter {

    public static final String NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";

    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newFactory();

    /** 주소 하나. {@code lastModified}가 null이면 {@code lastmod}를 넣지 않는다. */
    public record Entry(String loc, Instant lastModified) {
    }

    /** 사이트맵 색인({@code sitemapindex}). */
    public byte[] index(List<Entry> sitemaps) {
        return write("sitemapindex", "sitemap", sitemaps);
    }

    /** 주소 목록({@code urlset}). */
    public byte[] urlset(List<Entry> urls) {
        return write("urlset", "url", urls);
    }

    static String w3cDate(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    private static byte[] write(String root, String element, List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter xml = FACTORY.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement(root);
            xml.writeDefaultNamespace(NAMESPACE);
            for (Entry entry : entries) {
                xml.writeStartElement(element);
                xml.writeStartElement("loc");
                xml.writeCharacters(entry.loc());
                xml.writeEndElement();
                if (entry.lastModified() != null) {
                    xml.writeStartElement("lastmod");
                    xml.writeCharacters(w3cDate(entry.lastModified()));
                    xml.writeEndElement();
                }
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to write sitemap", e);
        }
        return out.toByteArray();
    }
}
