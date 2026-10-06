package net.java21.blog.backend.seo.service;

import java.time.Instant;

/** 사이트맵 응답 본문(UTF-8 XML)과 {@code Last-Modified}(모르면 null). */
public record SitemapDocument(byte[] xml, Instant lastModified) {
}
