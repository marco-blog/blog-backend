package net.java21.blog.backend.trackback.service;

import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.post.service.PostAccess;
import net.java21.blog.backend.post.service.PostUnlockCheck;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.dto.ManagedTrackbackResponse;
import net.java21.blog.backend.trackback.dto.TrackbackPingResponse;
import net.java21.blog.backend.trackback.dto.TrackbackResponse;
import net.java21.blog.backend.trackback.repository.TrackbackQueryRepository;
import net.java21.blog.backend.trackback.repository.TrackbackRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 받은 트랙백 목록·삭제와 보낸 기록(005 FR-049·051·052·053, contracts/api.md "트랙백").
 * <ul>
 *   <li>글 상세 목록: 글 상세를 볼 수 있을 때만(아니면 404 {@code POST_NOT_FOUND}). 잠긴 보호 글(004)은 빈 목록.</li>
 *   <li>삭제: 받은 글의 주인만(남의 글 403), ACTIVE만 DELETED로(없음·삭제·숨김 404 {@code TRACKBACK_NOT_FOUND}). 행은 남아 같은 주소의
 *       재수신을 막는다.</li>
 *   <li>관리 목록: 블로그 주인. 트랙백 받기를 꺼도 받은 것은 남는다.</li>
 *   <li>보낸 기록: 글 주인, 최신 {@value #PING_LIMIT}개.</li>
 * </ul>
 */
@Service
public class TrackbackService {

    static final int PING_LIMIT = 50;

    private final PostRepository postRepository;
    private final TrackbackRepository trackbackRepository;
    private final TrackbackQueryRepository queryRepository;
    private final BlogAccess blogAccess;
    private final PostAccess postAccess;

    public TrackbackService(PostRepository postRepository, TrackbackRepository trackbackRepository,
            TrackbackQueryRepository queryRepository, BlogAccess blogAccess, PostAccess postAccess) {
        this.postRepository = postRepository;
        this.trackbackRepository = trackbackRepository;
        this.queryRepository = queryRepository;
        this.blogAccess = blogAccess;
        this.postAccess = postAccess;
    }

    @Transactional(readOnly = true)
    public Page<TrackbackResponse> list(Long postId, Long viewerId, PostUnlockCheck unlock, Pageable pageable) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> PostExposure.isDetailVisibleTo(p, viewerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId));
        if (PostExposure.isLocked(post, viewerId, unlock.isUnlocked(post))) {
            return Page.empty(pageable);
        }
        return queryRepository.findVisible(postId, pageable).map(TrackbackResponse::of);
    }

    @Transactional
    public void delete(long userId, Long trackbackId) {
        Trackback trackback = trackbackRepository.findWithPostAndOwner(trackbackId)
                .filter(Trackback::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRACKBACK_NOT_FOUND,
                        "Trackback not found: " + trackbackId));
        if (!trackback.getPost().isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not the owner of trackback: " + trackbackId);
        }
        trackback.markDeleted();
    }

    @Transactional(readOnly = true)
    public Page<ManagedTrackbackResponse> managed(long userId, String handle, Pageable pageable) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        return queryRepository.findManaged(blog.getId(), pageable).map(ManagedTrackbackResponse::of);
    }

    @Transactional(readOnly = true)
    public List<TrackbackPingResponse> pings(long userId, Long postId) {
        postAccess.requireOwnedPost(postId, userId);
        return queryRepository.findRecentPings(postId, PING_LIMIT).stream().map(TrackbackPingResponse::of).toList();
    }
}
