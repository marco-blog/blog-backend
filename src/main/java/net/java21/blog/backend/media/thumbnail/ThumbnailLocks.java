package net.java21.blog.backend.media.thumbnail;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import org.springframework.stereotype.Component;

/**
 * 썸네일 키({@code {key}/{w}x{h}-{fit}})별 락(T217, FR-132). 같은 썸네일을 동시에 여러 번 요청해도 한 번만 만든다.
 * 락은 아무도 잡고 있지 않으면 사라지는 약한 참조이고, 오래 쓰지 않은 항목은 만료된다(맵이 끝없이 커지지 않게).
 */
@Component
public class ThumbnailLocks {

    private final LoadingCache<String, ReentrantLock> locks = Caffeine.newBuilder()
            .weakValues()
            .expireAfterAccess(Duration.ofMinutes(10))
            .build(key -> new ReentrantLock());

    public ReentrantLock lockFor(String thumbnailKey) {
        return locks.get(thumbnailKey);
    }
}
