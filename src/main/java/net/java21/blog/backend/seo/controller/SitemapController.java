package net.java21.blog.backend.seo.controller;

import net.java21.blog.backend.seo.service.SitemapDocument;
import net.java21.blog.backend.seo.service.SitemapService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사이트맵(002 contracts/api.md 사이트맵 절). {@code /api/v1} 밖의 경로이며 front 서버가 프록시한다. 성공은 XML 그대로,
 * 오류(404)는 공통 틀 JSON이다. 서버 캐시 없이 {@code Cache-Control: no-cache}와 {@code Last-Modified}를 준다.
 */
@RestController
public class SitemapController {

    static final MediaType XML_UTF8 = MediaType.parseMediaType("application/xml;charset=UTF-8");

    private final SitemapService sitemapService;

    public SitemapController(SitemapService sitemapService) {
        this.sitemapService = sitemapService;
    }

    @GetMapping("/sitemap.xml")
    ResponseEntity<byte[]> index() {
        return xml(sitemapService.index());
    }

    @GetMapping("/sitemap/pages.xml")
    ResponseEntity<byte[]> pages() {
        return xml(sitemapService.pages());
    }

    /** 숫자가 아니거나 너무 큰 {@code n}도 범위 밖(404)으로 본다. */
    @GetMapping("/sitemap/posts-{n:\\d+}.xml")
    ResponseEntity<byte[]> posts(@PathVariable String n) {
        return xml(sitemapService.posts(n.length() > 9 ? 0 : Integer.parseInt(n)));
    }

    private static ResponseEntity<byte[]> xml(SitemapDocument document) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(XML_UTF8)
                .cacheControl(CacheControl.noCache());
        if (document.lastModified() != null) {
            builder.lastModified(document.lastModified());
        }
        return builder.body(document.xml());
    }
}
