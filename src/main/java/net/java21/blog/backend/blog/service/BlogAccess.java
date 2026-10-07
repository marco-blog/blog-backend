package net.java21.blog.backend.blog.service;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 주소로 블로그를 찾고 볼 수 있는지·주인인지 판단하는 한 곳(contracts/api.md 블로그 절).
 * 삭제된 블로그와 정지·탈퇴 회원의 블로그는 주인에게도 404 {@code BLOG_NOT_FOUND}이고, 주인이 아니면 403 {@code FORBIDDEN}.
 * 돌려준 블로그의 주인({@code getUser()})은 이미 읽혀 있다.
 */
@Component
public class BlogAccess {

    private final BlogRepository blogRepository;

    public BlogAccess(BlogRepository blogRepository) {
        this.blogRepository = blogRepository;
    }

    /** 누구나 볼 수 있는 블로그(블로그 ACTIVE, 주인 ACTIVE). */
    @Transactional(readOnly = true)
    public Blog requireVisibleBlog(String handle) {
        return blogRepository.findByHandleWithOwner(handle)
                .filter(blog -> blog.isActive() && blog.getUser().isActive())
                .orElseThrow(() -> new BusinessException(ErrorCode.BLOG_NOT_FOUND, "Blog not found: " + handle));
    }

    /**
     * 블로그 첫 화면용({@code GET /blogs/{handle}}만, 005 FR-042): 블로그는 ACTIVE인데 주인이 정지(SUSPENDED)면 404
     * {@code BLOG_RESTRICTED}(front가 "이용이 제한된 블로그"로 안내). 그 밖에 볼 수 없으면 {@code BLOG_NOT_FOUND}.
     */
    @Transactional(readOnly = true)
    public Blog requireVisibleBlogForPage(String handle) {
        Blog blog = blogRepository.findByHandleWithOwner(handle)
                .filter(Blog::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.BLOG_NOT_FOUND, "Blog not found: " + handle));
        if (blog.getUser().isSuspended()) {
            throw new BusinessException(ErrorCode.BLOG_RESTRICTED, "Blog owner is suspended: " + handle);
        }
        if (!blog.getUser().isActive()) {
            throw new BusinessException(ErrorCode.BLOG_NOT_FOUND, "Blog not found: " + handle);
        }
        return blog;
    }

    /** 이 회원이 주인인 블로그. 볼 수 없는 블로그면 404, 주인이 아니면 403. */
    @Transactional(readOnly = true)
    public Blog requireOwnedActiveBlog(String handle, long userId) {
        Blog blog = requireVisibleBlog(handle);
        if (!blog.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not the owner of blog: " + handle);
        }
        return blog;
    }
}
