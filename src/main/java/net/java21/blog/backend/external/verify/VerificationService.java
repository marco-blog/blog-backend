package net.java21.blog.backend.external.verify;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.net.FetchResult;
import net.java21.blog.backend.common.net.SafeHttpFetcher;
import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalBlogVerification;
import net.java21.blog.backend.external.domain.FetchResultCode;
import net.java21.blog.backend.external.feed.FeedDiscovery;
import net.java21.blog.backend.external.feed.FeedParseException;
import net.java21.blog.backend.external.feed.FeedParser;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.feed.HtmlScanner;
import net.java21.blog.backend.external.feed.ParsedFeed;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalBlogVerificationRepository;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 소유 인증(007 FR-110, research E8). 코드는 같은 회원·같은 피드에 유효한 것이 있으면 다시 쓴다. 확인은 피드(채널 제목·설명, 앞쪽 항목
 * 20개)와 블로그 첫 화면 HTML(텍스트·{@code meta} 값) 두 곳에서 코드를 찾고, 외부 요청은 트랜잭션 밖에서 한다. 확인은 회원당 시간당
 * {@code verify-checks-per-hour}회.
 *
 * <p>스키마에 인증 행의 피드 주소 컬럼이 없어(해시만, data-model) 확인 때 받을 주소는 (1) 발급 때 기억한 주소, (2) 같은 해시의 등록,
 * (3) 요청 본문의 {@code feedUrl}(해시가 같아야 함) 순서로 정한다.
 */
@Service
public class VerificationService {

    static final Duration FEED_URL_MEMORY = Duration.ofHours(25);

    private final ExternalBlogVerificationRepository repository;
    private final ExternalBlogRepository blogRepository;
    private final UserRepository userRepository;
    private final VerificationCodeGenerator generator;
    private final FeedDiscovery discovery;
    private final SafeHttpFetcher fetcher;
    private final FeedParser parser;
    private final RateLimitPolicy rateLimits;
    private final ExternalFeedProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Cache<Long, String> feedUrls = Caffeine.newBuilder().expireAfterWrite(FEED_URL_MEMORY)
            .maximumSize(50_000).build();

    public VerificationService(ExternalBlogVerificationRepository repository, ExternalBlogRepository blogRepository,
            UserRepository userRepository, VerificationCodeGenerator generator, FeedDiscovery discovery,
            SafeHttpFetcher fetcher, FeedParser parser, RateLimitPolicy rateLimits, ExternalFeedProperties properties,
            TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.blogRepository = blogRepository;
        this.userRepository = userRepository;
        this.generator = generator;
        this.discovery = discovery;
        this.fetcher = fetcher;
        this.parser = parser;
        this.rateLimits = rateLimits;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    /** 발급 결과와 새로 만들었는지(201) 여부. */
    public record Issued(VerificationResponse verification, boolean created) {
    }

    /** 코드 발급(같은 회원·피드에 유효한 코드가 있으면 그것). 주소 검사는 미리보기와 같다. */
    public Issued issue(long userId, String feedUrl) {
        URI uri = discovery.requireAllowed("feedUrl", feedUrl);
        String hash = FeedUrlNormalizer.hash(uri.toString());
        return tx.execute(status -> {
            Instant now = clock.instant();
            Optional<ExternalBlogVerification> existing = repository.findValid(userId, hash, now);
            ExternalBlogVerification verification;
            boolean created = existing.isEmpty();
            if (existing.isPresent()) {
                verification = existing.get();
            } else {
                String code;
                do {
                    code = generator.next();
                } while (repository.existsByCode(code));
                verification = repository.saveAndFlush(new ExternalBlogVerification(
                        userRepository.getReferenceById(userId), hash, code, now.plus(properties.verificationTtl())));
            }
            feedUrls.put(verification.getId(), uri.toString());
            return new Issued(VerificationResponse.of(verification, uri.toString(),
                    verification.isVerified() ? claimable(userId, hash) : null), created);
        });
    }

    /**
     * 코드 확인. 이미 인증됐으면 외부 요청 없이 그대로, 남의 인증 404, 만료 422 {@code EXTERNAL_VERIFICATION_EXPIRED}, 못 찾으면 422
     * {@code EXTERNAL_VERIFICATION_CODE_NOT_FOUND}({@code checked}, {@code failures}).
     */
    public VerificationResponse check(long userId, long id, String feedUrlHint) {
        ExternalBlogVerification verification = tx.execute(status -> repository.findById(id)
                .filter(v -> v.getUser().getId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.EXTERNAL_VERIFICATION_NOT_FOUND,
                        "Verification not found: " + id)));
        String feedUrl = resolveFeedUrl(verification, feedUrlHint);
        if (verification.isVerified()) {
            return VerificationResponse.of(verification, feedUrl, claimable(userId, verification.getFeedUrlHash()));
        }
        Instant now = clock.instant();
        if (verification.isExpired(now)) {
            throw BusinessException.withParams(ErrorCode.EXTERNAL_VERIFICATION_EXPIRED, "Verification expired",
                    Map.of("expiredAt", verification.getExpiresAt().toString()));
        }
        rateLimits.check(RateLimitKind.EXTERNAL_VERIFY_CHECK, "u:" + userId);
        Map<String, String> failures = new LinkedHashMap<>();
        if (!found(URI.create(feedUrl), verification.getCode(), failures)) {
            throw BusinessException.withParams(ErrorCode.EXTERNAL_VERIFICATION_CODE_NOT_FOUND, "Code not found",
                    Map.of("checked", List.of("FEED", "SITE"), "failures", failures));
        }
        return tx.execute(status -> {
            ExternalBlogVerification managed = repository.findById(id).orElseThrow();
            managed.verify(clock.instant());
            repository.flush();
            return VerificationResponse.of(managed, feedUrl, claimable(userId, managed.getFeedUrlHash()));
        });
    }

    /** 피드에서, 없으면 블로그 첫 화면에서 코드를 찾는다. 받지 못한 곳은 {@code failures}에 결과 코드. */
    private boolean found(URI feedUri, String code, Map<String, String> failures) {
        FetchResult feedResult = fetcher.get(feedUri, SafeHttpFetcher.Limit.FEED);
        URI site = null;
        if (!feedResult.isSuccess() || feedResult.status() != 200) {
            failures.put("FEED", (feedResult.isSuccess() ? FetchResultCode.HTTP_ERROR
                    : FetchResultCode.of(feedResult.failure())).name());
        } else {
            try {
                if (parser.containsText(feedResult.body(), feedResult.contentType(), code)) {
                    return true;
                }
                ParsedFeed parsed = parser.parse(feedResult.body(), feedResult.contentType(), feedResult.finalUri(),
                        clock.instant(), 1);
                site = parsed.siteUrl() == null ? null : URI.create(parsed.siteUrl());
            } catch (FeedParseException | IllegalArgumentException e) {
                failures.put("FEED", FetchResultCode.PARSE_ERROR.name());
            }
        }
        if (site == null) {
            URI base = feedResult.finalUri() == null ? feedUri : feedResult.finalUri();
            site = URI.create(base.getScheme() + "://" + base.getRawAuthority() + "/");
        }
        FetchResult siteResult = fetcher.get(site, SafeHttpFetcher.Limit.PAGE);
        if (!siteResult.isSuccess() || siteResult.status() != 200 || siteResult.body() == null) {
            failures.put("SITE", (siteResult.isSuccess() ? FetchResultCode.HTTP_ERROR
                    : FetchResultCode.of(siteResult.failure())).name());
            return false;
        }
        HtmlScanner.Scan scan = HtmlScanner.scan(new String(siteResult.body(), charset(siteResult.contentType())),
                siteResult.finalUri());
        return scan.text().contains(code) || scan.metaValues().stream().anyMatch(v -> v != null && v.contains(code));
    }

    private static java.nio.charset.Charset charset(String contentType) {
        if (contentType != null) {
            int i = contentType.toLowerCase(java.util.Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                String name = contentType.substring(i + 8).replace("\"", "").split("[;\\s]")[0];
                try {
                    return java.nio.charset.Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    // 모르는 문자 집합은 UTF-8
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    private String resolveFeedUrl(ExternalBlogVerification verification, String hint) {
        String remembered = feedUrls.getIfPresent(verification.getId());
        if (remembered != null) {
            return remembered;
        }
        Optional<String> fromBlog = tx.execute(status -> blogRepository
                .findFirstByFeedUrlHashOrderByIdDesc(verification.getFeedUrlHash()).map(ExternalBlog::getFeedUrl));
        if (fromBlog != null && fromBlog.isPresent()) {
            feedUrls.put(verification.getId(), fromBlog.get());
            return fromBlog.get();
        }
        if (hint == null || hint.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("feedUrl", "REQUIRED")));
        }
        URI uri = discovery.requireAllowed("feedUrl", hint);
        if (!FeedUrlNormalizer.hash(uri.toString()).equals(verification.getFeedUrlHash())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("feedUrl", "INVALID")));
        }
        feedUrls.put(verification.getId(), uri.toString());
        return uri.toString();
    }

    /** 같은 피드의 다른 회원·미인증 활성 등록(넘겨받을 수 있음)의 id. */
    private Long claimable(long userId, String feedHash) {
        return tx.execute(status -> blogRepository.findHolding(feedHash)
                .filter(b -> ExternalBlogStatus.CLAIMABLE.contains(b.getStatus()))
                .filter(b -> !b.isManagedBy(userId) && !b.isOwnershipVerified())
                .map(ExternalBlog::getId)
                .orElse(null));
    }
}
