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
 * 끝까지 읽음(003 FR-086, research P4, 결정 표 16번). front 글 상세가 본문 끝에 닿으면 한 번 보낸다. 상세를 볼 수 있는 글만
 * (아니면 404 {@code POST_NOT_FOUND}), 같은 방문자 키로 {@code blog.posts.view-dedup-ttl}(30분) 안에 다시 오면 세지 않는다
 * (조회수와 같은 방식, 별도 Caffeine 캐시). 세면 그날(UTC)의 {@code post_daily_stats.read_completes}를 1 올린다.
 */
@Service
public class ReadCompleteService {

    private final PostRepository postRepository;
    private final PostDailyStatsRepository dailyStats;
    private final Clock clock;
    private final Cache<String, Boolean> recent;

    @Autowired
    public ReadCompleteService(PostRepository postRepository, PostDailyStatsRepository dailyStats,
            PostsProperties properties, Clock clock) {
        this(postRepository, dailyStats, properties, clock, Ticker.systemTicker());
    }

    /** 테스트에서 시간을 고정할 때. */
    ReadCompleteService(PostRepository postRepository, PostDailyStatsRepository dailyStats, PostsProperties properties,
            Clock clock, Ticker ticker) {
        this.postRepository = postRepository;
        this.dailyStats = dailyStats;
        this.clock = clock;
        this.recent = Caffeine.newBuilder()
                .expireAfterWrite(properties.viewDedupTtl().toNanos(), TimeUnit.NANOSECONDS)
                .maximumSize(properties.viewDedupMaxSize())
                .ticker(ticker)
                .build();
    }

    /**
     * @param viewerId   로그인한 회원(없으면 null)
     * @param visitorKey 중복 판단 키({@code u:{회원 ID}} 또는 {@code v:{방문자 쿠키}})
     * @return 이번 요청을 셌으면 true
     */
    @Transactional
    public boolean record(Long postId, Long viewerId, String visitorKey) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> PostExposure.isDetailVisibleTo(p, viewerId))
                .orElseThrow(() -> PostAccess.notFound(postId));
        if (recent.asMap().putIfAbsent(post.getId() + ":" + visitorKey, Boolean.TRUE) != null) {
            return false;
        }
        Instant now = clock.instant();
        dailyStats.upsertReadComplete(post.getId(), LocalDate.ofInstant(now, ZoneOffset.UTC), now);
        return true;
    }
}
