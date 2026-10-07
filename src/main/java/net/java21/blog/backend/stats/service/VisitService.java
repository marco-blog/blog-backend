package net.java21.blog.backend.stats.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.StatsProperties;
import net.java21.blog.backend.stats.repository.BlogVisitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 방문자 세기(004 FR-067, research B9). 같은 방문자(회원 ID 또는 방문자 쿠키)는 블로그마다 기준 시간대의 하루 한 번만 센다.
 * 중복 판단은 서버 1대 전제의 Caffeine 캐시(키에 날짜를 넣고 25시간 뒤 비움)이며, 첫 방문만 일별 행 upsert와 블로그 전체 수 증가를
 * 같은 트랜잭션에서 한다. 블로그 주인 본인, User-Agent가 비었거나 봇 규칙({@code blog.stats.bot-user-agent-pattern})에 맞으면 세지 않는다.
 */
@Service
public class VisitService {

    /** 날짜가 키에 들어 있으므로 하루보다 조금 길게 둔다. */
    static final Duration DEDUP_TTL = Duration.ofHours(25);

    private final BlogAccess blogAccess;
    private final BlogVisitRepository repository;
    private final BlogCalendar calendar;
    private final Clock clock;
    private final Pattern botPattern;
    private final Cache<String, Boolean> seen;

    @Autowired
    public VisitService(BlogAccess blogAccess, BlogVisitRepository repository, BlogCalendar calendar,
            StatsProperties properties, Clock clock) {
        this(blogAccess, repository, calendar, properties, clock, Ticker.systemTicker());
    }

    /** 테스트에서 캐시 시간을 움직일 때. */
    VisitService(BlogAccess blogAccess, BlogVisitRepository repository, BlogCalendar calendar,
            StatsProperties properties, Clock clock, Ticker ticker) {
        this.blogAccess = blogAccess;
        this.repository = repository;
        this.calendar = calendar;
        this.clock = clock;
        this.botPattern = properties.botPattern();
        this.seen = Caffeine.newBuilder()
                .expireAfterWrite(DEDUP_TTL.toNanos(), TimeUnit.NANOSECONDS)
                .maximumSize(properties.visitDedupMaxSize())
                .ticker(ticker)
                .build();
    }

    /**
     * 방문 한 번을 기록한다. 볼 수 없는 블로그면 404 {@code BLOG_NOT_FOUND}.
     *
     * @return 이번 방문을 셌으면 true
     */
    @Transactional
    public boolean record(String handle, Long viewerId, String visitorKey, String userAgent) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        if (blog.isOwnedBy(viewerId) || visitorKey == null || isBot(userAgent)) {
            return false;
        }
        Instant now = clock.instant();
        LocalDate date = calendar.dateOf(now);
        if (seen.asMap().putIfAbsent(blog.getId() + "|" + date + "|" + visitorKey, Boolean.TRUE) != null) {
            return false;
        }
        // 블로그 행 배타 잠금을 먼저 잡는다. 일별 행 INSERT의 외래 키 확인이 블로그 행 공유 잠금을 잡은 뒤 UPDATE blogs로
        // 올리면 동시 방문끼리 교착 상태가 된다(MySQL, BlogVisitConcurrencyTest).
        repository.incrementTotal(blog.getId());
        repository.upsertVisit(blog.getId(), date, now);
        return true;
    }

    boolean isBot(String userAgent) {
        return userAgent == null || userAgent.isBlank() || botPattern.matcher(userAgent).find();
    }
}
