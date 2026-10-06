package net.java21.blog.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 서비스 주소(contracts/api.md "프로퍼티" {@code blog.base-url}). 메일 링크 같은 절대 URL을 만들 때 쓴다.
 * 끝의 {@code /}는 떼어 둔다.
 */
@ConfigurationProperties("blog")
public record SiteProperties(@DefaultValue("https://blog.java21.net") String baseUrl) {

    public SiteProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("blog.base-url is required");
        }
        baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }

    /** {@code path}는 {@code /}로 시작한다. */
    public String url(String path) {
        return baseUrl + path;
    }
}
