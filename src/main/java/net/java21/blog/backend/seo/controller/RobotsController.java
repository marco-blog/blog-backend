package net.java21.blog.backend.seo.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import net.java21.blog.backend.config.SiteProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /robots.txt}(002 FR-037, research D5): 모든 크롤러를 허용하되 로그인·개인 화면과 {@code /search}·{@code /feed}·
 * {@code /notifications}를 막고, 마지막 줄에 사이트맵 주소를 둔다.
 * robots 규칙은 앞부분 일치라 {@code Disallow: /feed}는 {@code /feedback} 같은 블로그 주소까지 막는다. 그래서 한 단어 화면은
 * {@code $}(끝)·{@code ?}(쿼리)·{@code /}(하위 경로)로 끝을 고정한 규칙으로 쓴다(Google·Bing이 지원하는 확장).
 */
@RestController
public class RobotsController {

    static final MediaType TEXT_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    /** 한 단어 최상위 화면(하위 경로 포함). 001 contracts/routes.md 예약 경로 중 로그인·개인 화면. */
    static final List<String> PRIVATE_SCREENS = List.of("login", "signup", "logout", "settings", "write", "manage",
            "feed", "notifications", "search", "locale");

    private final SiteProperties site;

    public RobotsController(SiteProperties site) {
        this.site = site;
    }

    @GetMapping("/robots.txt")
    ResponseEntity<byte[]> robots() {
        StringBuilder body = new StringBuilder("User-agent: *\n");
        body.append("Disallow: /api/\n");
        body.append("Disallow: /password-reset\n");
        for (String screen : PRIVATE_SCREENS) {
            body.append("Disallow: /").append(screen).append("$\n");
            body.append("Disallow: /").append(screen).append("?\n");
            body.append("Disallow: /").append(screen).append("/\n");
        }
        // 블로그별 작성·관리 화면(/{handle}/write/{id}, /{handle}/manage/...)
        for (String screen : List.of("write", "manage")) {
            body.append("Disallow: /*/").append(screen).append("$\n");
            body.append("Disallow: /*/").append(screen).append("/\n");
        }
        body.append('\n').append("Sitemap: ").append(site.url("/sitemap.xml")).append('\n');
        return ResponseEntity.ok()
                .contentType(TEXT_UTF8)
                .cacheControl(CacheControl.noCache())
                .body(body.toString().getBytes(StandardCharsets.UTF_8));
    }
}
