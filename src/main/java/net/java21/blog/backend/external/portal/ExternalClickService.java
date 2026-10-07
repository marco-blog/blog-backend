package net.java21.blog.backend.external.portal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.repository.ExternalPostDailyClickRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 글 원문 이동과 클릭 집계(007 FR-124, research E14). 포털 노출 조건을 만족하는 글이면 링크를 돌려주고, 같은 방문자·같은 글은
 * {@code blog.external.click-dedupe-window}(30분)에 한 번만 센다(프로세스 안 Caffeine, 001 조회수와 같은 방식, 서버 1대 전제).
 * 세면 같은 트랜잭션에서 {@code external_posts.click_count + 1}과 그날(UTC) {@code external_post_daily_clicks} upsert(쿼리 2회).
 */
@Service
public class ExternalClickService {

    static final int DEDUPE_MAX = 100_000;

    private final ExternalPortalQueryRepository queries;
    private final ExternalPostRepository postRepository;
    private final ExternalPostDailyClickRepository dailyClicks;
    private final Clock clock;
    private final Cache<String, Boolean> recent;

    @Autowired
    public ExternalClickService(ExternalPortalQueryRepository queries, ExternalPostRepository postRepository,
            ExternalPostDailyClickRepository dailyClicks, ExternalFeedProperties properties, Clock clock) {
        this(queries, postRepository, dailyClicks, properties, clock, Ticker.systemTicker());
    }

    /** 시험에서 시간을 고정할 때. */
    ExternalClickService(ExternalPortalQueryRepository queries, ExternalPostRepository postRepository,
            ExternalPostDailyClickRepository dailyClicks, ExternalFeedProperties properties, Clock clock,
            Ticker ticker) {
        this.queries = queries;
        this.postRepository = postRepository;
        this.dailyClicks = dailyClicks;
        this.clock = clock;
        this.recent = Caffeine.newBuilder()
                .expireAfterWrite(properties.clickDedupeWindow().toNanos(), TimeUnit.NANOSECONDS)
                .maximumSize(DEDUPE_MAX)
                .ticker(ticker)
                .build();
    }

    /**
     * @param visitorKey 중복 판단 키(회원 {@code u:}, 방문자 쿠키 {@code v:}, 둘 다 없으면 IP 해시 {@code ip:})
     * @return 원문 링크
     * @throws BusinessException 포털 노출 조건을 만족하지 않으면 404 {@code EXTERNAL_POST_NOT_FOUND}
     */
    @Transactional
    public String visit(long postId, String visitorKey) {
        Instant now = clock.instant();
        Optional<String> link = queries.findVisibleLink(postId, now);
        if (link.isEmpty()) {
            throw new BusinessException(ErrorCode.EXTERNAL_POST_NOT_FOUND, "External post not found: " + postId);
        }
        if (visitorKey != null && recent.asMap().putIfAbsent(postId + ":" + visitorKey, Boolean.TRUE) == null) {
            postRepository.incrementClick(postId);
            dailyClicks.upsertClick(postId, LocalDate.ofInstant(now, ZoneOffset.UTC), now);
        }
        return link.get();
    }
}
