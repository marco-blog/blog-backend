package net.java21.blog.backend.seo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 사이트맵 설정(002 contracts/api.md "프로퍼티", research D5).
 *
 * @param urlsPerFile  {@code /sitemap/posts-{n}.xml} 파일 하나의 주소 수(표준 상한 50,000)
 */
@ConfigurationProperties("blog.sitemap")
public record SitemapProperties(@DefaultValue("50000") int urlsPerFile) {

    public static final int MAX_URLS_PER_FILE = 50_000;

    public SitemapProperties {
        if (urlsPerFile < 1 || urlsPerFile > MAX_URLS_PER_FILE) {
            throw new IllegalArgumentException("blog.sitemap.urls-per-file must be 1.." + MAX_URLS_PER_FILE);
        }
    }
}
