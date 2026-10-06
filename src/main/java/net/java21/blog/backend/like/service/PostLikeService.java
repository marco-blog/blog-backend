package net.java21.blog.backend.like.service;

import java.time.Clock;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.like.dto.LikeStateResponse;
import net.java21.blog.backend.like.repository.PostLikeRepository;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요(002 FR-030, contracts/api.md 좋아요 절, research D1).
 * <ul>
 *   <li>누르기: 발행되었고 이 회원이 상세를 볼 수 있는 글만({@link PostExposure#isDetailVisibleTo}). 아니면 404 {@code POST_NOT_FOUND}.
 *       자기 글도 누를 수 있다. 이미 누른 글이면 수를 바꾸지 않고 같은 응답(멱등).</li>
 *   <li>취소: 글 상태와 관계없이 된다(비공개로 바뀐 뒤에도). 누르지 않은 글이어도 200, 글이 아예 없으면 404.</li>
 *   <li>행 추가·삭제와 {@code posts.like_count} ±1은 한 트랜잭션이며 행이 실제로 생기거나 지워질 때만 수를 바꾼다.</li>
 * </ul>
 */
@Service
public class PostLikeService {

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final Clock clock;

    public PostLikeService(PostRepository postRepository, PostLikeRepository postLikeRepository, Clock clock) {
        this.postRepository = postRepository;
        this.postLikeRepository = postLikeRepository;
        this.clock = clock;
    }

    /** 쿼리: 글(블로그·주인) 1 + 잠금·수 1 + INSERT 1 + 새로 눌렀으면 UPDATE 1. */
    @Transactional
    public LikeStateResponse like(long userId, Long postId) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(Post::isPublished)
                .filter(p -> PostExposure.isDetailVisibleTo(p, userId))
                .orElseThrow(() -> notFound(postId));
        int count = postLikeRepository.lockLikeCount(post.getId()).orElseThrow(() -> notFound(postId));
        if (postLikeRepository.insertIgnore(userId, post.getId(), clock.instant()) == 1) {
            postLikeRepository.changeLikeCount(post.getId(), 1);
            count++;
        }
        return new LikeStateResponse(post.getId(), true, count);
    }

    /** 쿼리: 잠금·수 1 + DELETE 1 + 지웠으면 UPDATE 1. */
    @Transactional
    public LikeStateResponse unlike(long userId, Long postId) {
        int count = postLikeRepository.lockLikeCount(postId).orElseThrow(() -> notFound(postId));
        if (postLikeRepository.delete(userId, postId) == 1) {
            postLikeRepository.changeLikeCount(postId, -1);
            count = Math.max(0, count - 1);
        }
        return new LikeStateResponse(postId, false, count);
    }

    /** 이 회원이 이 글에 좋아요를 눌렀는지. 비로그인이면 null. 쿼리 0~1회. */
    @Transactional(readOnly = true)
    public Boolean isLikedBy(Long userId, Long postId) {
        return userId == null ? null : postLikeRepository.existsByUserIdAndPostId(userId, postId);
    }

    private static BusinessException notFound(Long postId) {
        return new BusinessException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId);
    }
}
