package net.java21.blog.backend.post.service;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.security.AttemptTarget;
import net.java21.blog.backend.common.security.PasswordAttemptGuard;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보호 글 열기(004 FR-062, FR-063, research B3·B4). 목록 노출 가능한 보호 글이 아니면 404 {@code POST_NOT_FOUND}.
 * 같은 글에 같은 방문자 키 또는 IP가 5회 연속 틀리면 10분 동안 429 {@code PASSWORD_ATTEMPTS_EXCEEDED}({@link PasswordAttemptGuard}),
 * 틀리면 400 {@code POST_PASSWORD_MISMATCH}, 맞으면 본문을 포함한 상세와 30분 열람 쿠키({@link PostUnlockCookies})를 준다.
 */
@Service
public class PostUnlockService {

    private final PostRepository postRepository;
    private final PostService postService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordAttemptGuard attemptGuard;
    private final PostUnlockCookies cookies;

    public PostUnlockService(PostRepository postRepository, PostService postService, PasswordEncoder passwordEncoder,
            PasswordAttemptGuard attemptGuard, PostUnlockCookies cookies) {
        this.postRepository = postRepository;
        this.postService = postService;
        this.passwordEncoder = passwordEncoder;
        this.attemptGuard = attemptGuard;
        this.cookies = cookies;
    }

    /** 연 결과: 본문을 포함한 상세와 내려줄 열람 쿠키. */
    public record Unlocked(PostDetailResponse detail, ResponseCookie cookie) {
    }

    @Transactional(readOnly = true)
    public Unlocked unlock(Long postId, Long viewerId, String password, String visitorKey, String ip) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(PostExposure::isListable)
                .filter(Post::isProtected)
                .orElseThrow(() -> PostAccess.notFound(postId));
        AttemptTarget target = AttemptTarget.post(post.getId());
        attemptGuard.check(target, visitorKey, ip);
        if (password == null || password.isEmpty() || post.getPasswordHash() == null
                || !passwordEncoder.matches(password, post.getPasswordHash())) {
            attemptGuard.recordFailure(target, visitorKey, ip);
            throw new BusinessException(ErrorCode.POST_PASSWORD_MISMATCH, "Post password mismatch: " + postId);
        }
        attemptGuard.recordSuccess(target, visitorKey, ip);
        return new Unlocked(postService.detailOf(post, viewerId, false), cookies.issue(post));
    }
}
