package net.java21.blog.backend.external.feed;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.net.BlockReason;
import net.java21.blog.backend.common.net.FetchResult;
import net.java21.blog.backend.common.net.SafeHttpFetcher;
import net.java21.blog.backend.external.domain.FetchResultCode;
import org.springframework.stereotype.Component;

/**
 * 회원·운영자가 넣은 주소에서 피드를 찾는다(007 research E3, FR-109). (1) 받은 응답이 피드면 그 주소, (2) HTML이면
 * {@code <link rel="alternate">}의 첫 RSS·Atom, (3) 없으면 사이트 루트의 {@code /rss}·{@code /feed}. 외부 요청은 모두
 * {@link SafeHttpFetcher}로만 한다(내부망·우리 서비스 주소 금지, 리다이렉트마다 검사).
 */
@Component
public class FeedDiscovery {

    /** 주소 길이 상한(contracts/api.md). */
    public static final int URL_MAX = 1000;
    /** 미리보기에 쓰는 최근 글 수. */
    public static final int PREVIEW_ITEMS = 3;
    /** 신청·직접 등록 때 읽힘만 확인하는 항목 수. */
    static final int CHECK_ITEMS = 1;
    private static final List<String> GUESSES = List.of("/rss", "/feed");

    /**
     * 찾은 피드.
     *
     * @param feedUri 리다이렉트를 따라간 최종 피드 주소
     * @param fetch   피드 응답
     * @param feed    읽은 피드(항목은 요청한 수만큼)
     */
    public record Discovered(URI feedUri, FetchResult fetch, ParsedFeed feed) {
    }

    private final SafeHttpFetcher fetcher;
    private final FeedParser parser;
    private final Clock clock;

    public FeedDiscovery(SafeHttpFetcher fetcher, FeedParser parser, Clock clock) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.clock = clock;
    }

    /**
     * 입력 주소를 검사해 요청할 주소로 바꾼다. 비었거나 1000자를 넘으면 400 {@code VALIDATION_FAILED}(field), 형식·스킴·포트·
     * {@code user@}·내부망·우리 서비스 주소면 422 {@code EXTERNAL_FEED_URL_NOT_ALLOWED}({@code reason}).
     */
    public URI requireAllowed(String field, String input) {
        String value = input == null ? "" : input.strip();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of(field, "REQUIRED")));
        }
        if (value.length() > URL_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(new FieldError(field, "TOO_LONG", Map.of("max", URL_MAX))));
        }
        URI uri;
        try {
            uri = FeedUrlNormalizer.toUri(value);
        } catch (IllegalArgumentException e) {
            throw notAllowed(BlockReason.INVALID_URL);
        }
        BlockReason reason = fetcher.check(uri);
        if (reason != null) {
            throw notAllowed(reason);
        }
        return uri;
    }

    /** 블로그 주소 또는 피드 주소에서 피드를 찾는다(미리보기). 최근 글은 {@link #PREVIEW_ITEMS}편까지 읽는다. */
    public Discovered discover(URI input) {
        Instant now = clock.instant();
        FetchResult first = fetcher.get(input, SafeHttpFetcher.Limit.FEED);
        if (!first.isSuccess()) {
            throw unreadable(first);
        }
        int tried = 1;
        ParsedFeed direct = tryParse(first, now, PREVIEW_ITEMS);
        if (direct != null) {
            return new Discovered(first.finalUri(), first, direct);
        }
        if (looksLikeXml(first)) {
            throw unreadable(FetchResultCode.PARSE_ERROR, null);
        }
        URI base = first.finalUri() == null ? input : first.finalUri();
        List<URI> candidates = new ArrayList<>();
        HtmlScanner.Scan scan = HtmlScanner.scan(text(first), base);
        if (!scan.feedLinks().isEmpty()) {
            URI link = scan.feedLinks().get(0).href();
            FetchResult result = fetcher.get(link, SafeHttpFetcher.Limit.FEED);
            if (!result.isSuccess()) {
                throw unreadable(result);
            }
            ParsedFeed feed = tryParse(result, now, PREVIEW_ITEMS);
            if (feed == null) {
                throw unreadable(FetchResultCode.PARSE_ERROR, null);
            }
            return new Discovered(result.finalUri(), result, feed);
        }
        String root = base.getScheme() + "://" + base.getRawAuthority();
        for (String guess : GUESSES) {
            URI uri = URI.create(root + guess);
            if (uri.equals(input) || uri.equals(base)) {
                continue;
            }
            candidates.add(uri);
        }
        for (URI candidate : candidates) {
            tried++;
            FetchResult result = fetcher.get(candidate, SafeHttpFetcher.Limit.FEED);
            if (!result.isSuccess() || result.status() != 200) {
                continue;
            }
            ParsedFeed feed = tryParse(result, now, PREVIEW_ITEMS);
            if (feed != null) {
                return new Discovered(result.finalUri(), result, feed);
            }
        }
        throw BusinessException.withParams(ErrorCode.EXTERNAL_FEED_NOT_FOUND, "No feed found", Map.of("tried", tried));
    }

    /**
     * 피드 주소를 그대로 받아 읽히는지 확인한다(신청·직접 등록, research E8). 받지 못하거나 읽지 못하면 422
     * {@code EXTERNAL_FEED_UNREADABLE}({@code result}, {@code httpStatus?}).
     */
    public Discovered readFeed(URI feedUri) {
        FetchResult result = fetcher.get(feedUri, SafeHttpFetcher.Limit.FEED);
        if (!result.isSuccess()) {
            throw unreadable(result);
        }
        ParsedFeed feed = tryParse(result, clock.instant(), CHECK_ITEMS);
        if (feed == null) {
            throw unreadable(FetchResultCode.PARSE_ERROR, null);
        }
        return new Discovered(result.finalUri(), result, feed);
    }

    private ParsedFeed tryParse(FetchResult result, Instant now, int maxItems) {
        if (result.body() == null || result.status() != 200) {
            return null;
        }
        try {
            return parser.parse(result.body(), result.contentType(), result.finalUri(), now, maxItems);
        } catch (FeedParseException e) {
            return null;
        }
    }

    private static boolean looksLikeXml(FetchResult result) {
        String type = result.contentType() == null ? "" : result.contentType().toLowerCase(Locale.ROOT);
        if (type.contains("html")) {
            return false;
        }
        if (type.contains("xml") || type.contains("rss") || type.contains("atom")) {
            return true;
        }
        String head = text(result).stripLeading();
        return head.startsWith("<?xml") && !head.regionMatches(true, 0, "<!doctype html", 0, 14);
    }

    private static String text(FetchResult result) {
        return result.body() == null ? "" : new String(result.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 422 {@code EXTERNAL_FEED_URL_NOT_ALLOWED}. */
    public static BusinessException notAllowed(BlockReason reason) {
        return BusinessException.withParams(ErrorCode.EXTERNAL_FEED_URL_NOT_ALLOWED, "URL not allowed: " + reason,
                Map.of("reason", reason.name()));
    }

    /** 받지 못한 응답의 422 {@code EXTERNAL_FEED_UNREADABLE}. 우리 서비스·내부망으로 리다이렉트되면 주소 거부로. */
    public static BusinessException unreadable(FetchResult result) {
        if (result.blockReason() != null && result.blockReason() != BlockReason.PRIVATE_ADDRESS) {
            return notAllowed(result.blockReason());
        }
        return unreadable(FetchResultCode.of(result.failure()), result.httpStatusOrNull());
    }

    public static BusinessException unreadable(FetchResultCode code, Integer httpStatus) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("result", code.name());
        if (httpStatus != null) {
            params.put("httpStatus", httpStatus);
        }
        return BusinessException.withParams(ErrorCode.EXTERNAL_FEED_UNREADABLE, "Feed unreadable: " + code, params);
    }
}
