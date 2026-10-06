package net.java21.blog.backend.post.service;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostRepository;
import org.springframework.stereotype.Component;

/**
 * {@code /posts/{id}/...} 주인 API의 글 찾기·소유 확인 한 곳. "주인"은 그 글이 속한 블로그의 주인이다(contracts/api.md 글 절).
 * 없는 글, 삭제된 블로그·정지·탈퇴 회원의 글은 404 {@code POST_NOT_FOUND}, 주인이 아니면 403 {@code FORBIDDEN}.
 * 돌려준 글의 블로그와 주인은 이미 읽혀 있다(쿼리 1회).
 */
@Component
public class PostAccess {

    private final PostRepository postRepository;

    public PostAccess(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    /** 주인의 글(휴지통 글 포함). */
    public Post requireOwnedPost(Long postId, long userId) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> p.getBlog().isActive() && p.getBlog().getUser().isActive())
                .orElseThrow(() -> notFound(postId));
        if (!post.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not the owner of post: " + postId);
        }
        return post;
    }

    /** 주인이 고칠 수 있는 글(휴지통 글은 상세처럼 404). */
    public Post requireOwnedEditablePost(Long postId, long userId) {
        Post post = requireOwnedPost(postId, userId);
        if (post.isDeleted()) {
            throw notFound(postId);
        }
        return post;
    }

    static BusinessException notFound(Long postId) {
        return new BusinessException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId);
    }
}
