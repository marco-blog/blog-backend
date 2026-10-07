package net.java21.blog.backend.post.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostDailyStatsRepository;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회수(FR-020, research R10). 키 = 글 id + 방문자 키(회원 ID 또는 방문자 쿠키 ID). 같은 키로 {@code blog.posts.view-dedup-ttl}(30분)
 * 안에 다시 오면 세지 않는다. 판단은 프로세스 안 Caffeine 캐시(서버 1대 전제), 증가는 원자적 UPDATE 1회.
 * 상세를 볼 수 없는 글은 404 {@code POST_NOT_FOUND}. 조회수를 올린 같은 트랜잭션에서 그날(UTC)의 {@code post_daily_stats.views}도
 * 1 올린다(003 인기 점수, research P4).
 */
@Service
public class ViewCountService {

    private final PostRepository postRepository;
    private final PostDailyStatsRepository dailyStats;
    private final Clock clock;
    private final Cache<String, Boolean> recentViews;

    @Autowired
    public ViewCountService(PostRepository postRepository, PostDailyStatsRepository dailyStats,
            PostsProperties properties, Clock clock) {
        this(postRepository, dailyStats, properties, clock, Ticker.systemTicker());
    }

    /** 테스트에서 시간을 고정할 때. */
    ViewCountService(PostRepository postRepository, PostDailyStatsRepository dailyStats, PostsProperties properties,
            Clock clock, Ticker ticker) {
        this.postRepository = postRepository;
        this.dailyStats = dailyStats;
        this.clock = clock;
        this.recentViews = Caffeine.newBuilder()
                .expireAfterWrite(properties.viewDedupTtl().toNanos(), TimeUnit.NANOSECONDS)
                .maximumSize(properties.viewDedupMaxSize())
                .ticker(ticker)
                .build();
    }

    /**
     * 조회 한 번을 기록한다.
     *
     * @param viewerId   로그인한 회원(없으면 null). 주인은 자기 비공개·임시저장 글도 볼 수 있다
     * @param visitorKey 중복 판단 키({@code u:{회원 ID}} 또는 {@code v:{방문자 쿠키}})
     * @return 이번 조회로 조회수가 늘었으면 true
     */
    @Transactional
    public boolean record(Long postId, Long viewerId, String visitorKey) {
        return record(postId, viewerId, visitorKey, PostUnlockCheck.NONE);
    }

    /** 열지 않은 보호 글(004)은 200이지만 세지 않는다(contracts/api.md 보호 글 절). */
    @Transactional
    public boolean record(Long postId, Long viewerId, String visitorKey, PostUnlockCheck unlock) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> PostExposure.isDetailVisibleTo(p, viewerId))
                .orElseThrow(() -> PostAccess.notFound(postId));
        if (PostExposure.isLocked(post, viewerId, unlock.isUnlocked(post))) {
            return false;
        }
        String key = post.getId() + ":" + visitorKey;
        if (recentViews.asMap().putIfAbsent(key, Boolean.TRUE) != null) {
            return false;
        }
        postRepository.incrementViewCount(post.getId());
        Instant now = clock.instant();
        dailyStats.upsertView(post.getId(), LocalDate.ofInstant(now, ZoneOffset.UTC), now);
        return true;
    }
}
