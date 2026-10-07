package net.java21.blog.backend.external.member;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.feed.FeedDiscovery;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.feed.ParsedFeed;
import net.java21.blog.backend.external.member.dto.FeedPreviewResponse;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimitPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 블로그 미리보기(007 FR-109, research E3·E8). 회원당 시간당 {@code blog.external.preview-per-hour}회(넘으면 429), 같은 주소는
 * 10분 동안 외부 요청 없이 캐시한 결과를 쓴다(입력 주소와 찾은 피드 주소 두 키). 이미 등록된 피드인지는 캐시하지 않고 매번 본다.
 */
@Service
public class PreviewService {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final long CACHE_MAX = 10_000;

    private final FeedDiscovery discovery;
    private final ExternalBlogRepository blogRepository;
    private final RateLimitPolicy rateLimits;
    private final Cache<String, FeedPreviewResponse> cache;

    @Autowired
    public PreviewService(FeedDiscovery discovery, ExternalBlogRepository blogRepository, RateLimitPolicy rateLimits) {
        this(discovery, blogRepository, rateLimits, Ticker.systemTicker());
    }

    public PreviewService(FeedDiscovery discovery, ExternalBlogRepository blogRepository, RateLimitPolicy rateLimits,
            Ticker ticker) {
        this.discovery = discovery;
        this.blogRepository = blogRepository;
        this.rateLimits = rateLimits;
        this.cache = Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).maximumSize(CACHE_MAX).ticker(ticker).build();
    }

    @Transactional(readOnly = true)
    public FeedPreviewResponse preview(long userId, String url) {
        URI input = discovery.requireAllowed("url", url);
        rateLimits.check(RateLimitKind.EXTERNAL_PREVIEW, "u:" + userId);
        String key = FeedUrlNormalizer.hash(input.toString());
        FeedPreviewResponse core = cache.getIfPresent(key);
        if (core == null) {
            FeedDiscovery.Discovered found = discovery.discover(input);
            core = toResponse(found);
            cache.put(key, core);
            cache.put(FeedUrlNormalizer.hash(core.feedUrl()), core);
        }
        ExternalBlog holding = blogRepository.findHolding(FeedUrlNormalizer.hash(core.feedUrl())).orElse(null);
        return core.withRegistered(holding == null ? null
                : new FeedPreviewResponse.Registered(holding.getId(), holding.getStatus(),
                        holding.getStatus() != ExternalBlogStatus.BLOCKED, holding.isManagedBy(userId)));
    }

    private static FeedPreviewResponse toResponse(FeedDiscovery.Discovered found) {
        ParsedFeed feed = found.feed();
        List<FeedPreviewResponse.RecentPost> recent = feed.items().stream()
                .limit(FeedDiscovery.PREVIEW_ITEMS)
                .map(item -> new FeedPreviewResponse.RecentPost(item.title(), item.link(), item.publishedAt()))
                .toList();
        return new FeedPreviewResponse(found.feedUri().toString(), feed.siteUrl(), feed.title(), feed.format(), recent,
                null);
    }
}
