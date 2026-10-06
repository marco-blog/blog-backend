package net.java21.blog.backend.syndication.controller;

import java.util.function.Function;

import jakarta.servlet.http.HttpServletResponse;

import net.java21.blog.backend.syndication.service.FeedPlan;
import net.java21.blog.backend.syndication.service.FeedService;
import net.java21.blog.backend.syndication.service.FeedSnapshot;
import net.java21.blog.backend.syndication.writer.AtomFeedWriter;
import net.java21.blog.backend.syndication.writer.RssFeedWriter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * 블로그 피드(002 contracts/api.md 블로그 피드 절, FR-044·045·048). {@code /api/v1} 밖의 경로이며 front 서버가 프록시한다.
 * 담을 글의 버전으로 만든 약한 {@code ETag}·{@code Last-Modified}로 조건부 요청이면 본문을 읽지 않고 304를 준다
 * ({@link ServletWebRequest#checkNotModified(String, long)}). 서버 캐시는 없고 {@code Cache-Control: no-cache}로 매번 재검증하게 한다.
 * 성공은 XML, 오류(404)는 공통 틀 JSON이다.
 */
@RestController
public class FeedController {

    private static final MediaType RSS = MediaType.parseMediaType(RssFeedWriter.CONTENT_TYPE);
    private static final MediaType ATOM = MediaType.parseMediaType(AtomFeedWriter.CONTENT_TYPE);

    private final FeedService feedService;
    private final RssFeedWriter rssWriter;
    private final AtomFeedWriter atomWriter;

    public FeedController(FeedService feedService, RssFeedWriter rssWriter, AtomFeedWriter atomWriter) {
        this.feedService = feedService;
        this.rssWriter = rssWriter;
        this.atomWriter = atomWriter;
    }

    @GetMapping("/{handle}/rss")
    ResponseEntity<byte[]> rss(@PathVariable String handle, ServletWebRequest request) {
        return feed(feedService.plan(handle, null), request, RSS, rssWriter::write);
    }

    @GetMapping("/{handle}/atom")
    ResponseEntity<byte[]> atom(@PathVariable String handle, ServletWebRequest request) {
        return feed(feedService.plan(handle, null), request, ATOM, atomWriter::write);
    }

    @GetMapping("/{handle}/category/{categoryId:\\d+}/rss")
    ResponseEntity<byte[]> categoryRss(@PathVariable String handle, @PathVariable Long categoryId,
            ServletWebRequest request) {
        return feed(feedService.plan(handle, categoryId), request, RSS, rssWriter::write);
    }

    /** 바뀌지 않았으면 null(304, {@code checkNotModified}가 상태와 ETag·Last-Modified를 이미 정했다). */
    private ResponseEntity<byte[]> feed(FeedPlan plan, ServletWebRequest request, MediaType type,
            Function<FeedSnapshot, byte[]> writer) {
        HttpServletResponse response = request.getResponse();
        if (response != null) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noCache().getHeaderValue());
        }
        long lastModified = plan.lastModified() == null ? -1 : plan.lastModified().toEpochMilli();
        if (request.checkNotModified(plan.etag(), lastModified)) {
            return null;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noCache())
                .body(writer.apply(feedService.snapshot(plan)));
    }
}
