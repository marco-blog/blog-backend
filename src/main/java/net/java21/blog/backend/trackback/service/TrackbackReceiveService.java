package net.java21.blog.backend.trackback.service;

import java.util.Optional;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.trackback.TrackbackText;
import net.java21.blog.backend.trackback.TrackbackUrls;
import net.java21.blog.backend.trackback.TrackbackVisibility;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.repository.TrackbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 트랙백 받기(005 FR-049~051, FR-053~055, research M13). 확인 순서: 같은 출처 IP 한도 → 글 존재·handle 일치·본문 노출 가능 → 블로그
 * {@code trackback_enabled} → {@code url} → 같은 글에 같은 주소(정규화 SHA-256, 삭제·숨김 행 포함) → 저장. 볼 수 없는 글과 트랙백을 끈
 * 블로그는 존재를 숨기려고 같은 "허용 안 함"이다. 실패는 저장하지 않는다.
 * <p>저장은 짧은 트랜잭션 하나이며, 동시에 같은 핑이 와서 UNIQUE 제약에 걸리면 트랜잭션 밖에서 잡아 중복으로 답한다.
 * <p>서비스 안 글이 보낸 트랙백({@link #receiveInternal})은 IP 한도 없이 같은 규칙으로 받고 {@code source_post_id}를 채운다.
 */
@Service
public class TrackbackReceiveService {

    private static final Logger log = LoggerFactory.getLogger(TrackbackReceiveService.class);

    private final PostRepository postRepository;
    private final TrackbackRepository trackbackRepository;
    private final RateLimitPolicy rateLimits;
    private final TrackbackUrls urls;
    private final TransactionTemplate transactionTemplate;

    public TrackbackReceiveService(PostRepository postRepository, TrackbackRepository trackbackRepository,
            RateLimitPolicy rateLimits, TrackbackUrls urls, TransactionTemplate transactionTemplate) {
        this.postRepository = postRepository;
        this.trackbackRepository = trackbackRepository;
        this.rateLimits = rateLimits;
        this.urls = urls;
        this.transactionTemplate = transactionTemplate;
    }

    /** 밖에서 온 핑. {@code ip}는 보낸 곳 IP(암호화해 저장). */
    public ReceiveOutcome receive(String handle, long postId, PingForm form, String ip) {
        if (!rateLimits.tryAcquire(RateLimitKind.TRACKBACK_RECEIVE, "ip:" + ip)) {
            return ReceiveOutcome.TOO_MANY_PINGS;
        }
        return store(handle, postId, form, null, ip);
    }

    /**
     * 서비스 안 글이 보낸 트랙백(005 research M15). {@code source}는 블로그·주인을 이미 읽은 보낸 글이다. IP 한도는 세지 않는다.
     */
    public ReceiveOutcome receiveInternal(Post source, long targetPostId) {
        return receiveInternal(source, null, targetPostId);
    }

    /** 서비스 안 주소의 handle까지 맞춰 보는 내부 수신({@code handle}이 null이면 보지 않음). */
    public ReceiveOutcome receiveInternal(Post source, String handle, long targetPostId) {
        String sourceUrl = urls.postUrl(source.getBlog().getHandle(), source.getId());
        PingForm form = new PingForm(sourceUrl, source.getTitle(), source.getSummary(), source.getBlog().getTitle());
        return store(handle, targetPostId, form, source, null);
    }

    private ReceiveOutcome store(String handle, long postId, PingForm form, Post source, String ip) {
        try {
            ReceiveOutcome outcome = transactionTemplate.execute(status -> save(handle, postId, form, source, ip));
            return outcome == null ? ReceiveOutcome.NOT_ALLOWED : outcome;
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent duplicate trackback for post {}", postId);
            return ReceiveOutcome.DUPLICATE;
        }
    }

    private ReceiveOutcome save(String handle, long postId, PingForm form, Post source, String ip) {
        Optional<Post> found = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> handle == null || p.getBlog().getHandle().equals(handle))
                .filter(TrackbackVisibility::acceptsPings);
        if (found.isEmpty()) {
            return ReceiveOutcome.NOT_ALLOWED;
        }
        Post post = found.get();
        if (source != null && source.getId().equals(post.getId())) {
            return ReceiveOutcome.NOT_ALLOWED;
        }
        if (form.url() == null || form.url().isBlank()) {
            return ReceiveOutcome.MISSING_URL;
        }
        Optional<TrackbackUrls.Normalized> url = TrackbackUrls.normalize(form.url());
        if (url.isEmpty()) {
            return ReceiveOutcome.INVALID_URL;
        }
        if (trackbackRepository.existsByPostIdAndSourceUrlHash(post.getId(), url.get().hash())) {
            return ReceiveOutcome.DUPLICATE;
        }
        String title = TrackbackText.plain(form.title(), Trackback.TEXT_MAX);
        String excerpt = TrackbackText.plain(form.excerpt(), Trackback.TEXT_MAX);
        String blogName = TrackbackText.plain(form.blogName(), Trackback.TEXT_MAX);
        String sourceUrl = url.get().original();
        if (title == null) {
            title = TrackbackText.truncate(sourceUrl, Trackback.TEXT_MAX);
        }
        trackbackRepository.saveAndFlush(new Trackback(post, source, sourceUrl, url.get().hash(), title, excerpt,
                blogName, ip));
        return ReceiveOutcome.ACCEPTED;
    }
}
