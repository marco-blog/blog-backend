package net.java21.blog.backend.post.service;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회수(FR-020, research R10). 키 = 글 id + 방문자 키(회원 ID 또는 방문자 쿠키 ID). 같은 키로 {@code blog.posts.view-dedup-ttl}(30분)
 * 안에 다시 오면 세지 않는다. 판단은 프로세스 안 Caffeine 캐시(서버 1대 전제), 증가는 원자적 UPDATE 1회.
 * 상세를 볼 수 없는 글은 404 {@code POST_NOT_FOUND}.
 */
@Service
public class ViewCountService {

    private final PostRepository postRepository;
    private final Cache<String, Boolean> recentViews;

    @Autowired
    public ViewCountService(PostRepository postRepository, PostsProperties properties) {
        this(postRepository, properties, Ticker.systemTicker());
    }

    /** 테스트에서 시간을 고정할 때. */
    ViewCountService(PostRepository postRepository, PostsProperties properties, Ticker ticker) {
        this.postRepository = postRepository;
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
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> PostExposure.isDetailVisibleTo(p, viewerId))
                .orElseThrow(() -> PostAccess.notFound(postId));
        String key = post.getId() + ":" + visitorKey;
        if (recentViews.asMap().putIfAbsent(key, Boolean.TRUE) != null) {
            return false;
        }
        postRepository.incrementViewCount(post.getId());
        return true;
    }
}
