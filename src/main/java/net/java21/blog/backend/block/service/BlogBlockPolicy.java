package net.java21.blog.backend.block.service;

import net.java21.blog.backend.block.domain.BlogBlockId;
import net.java21.blog.backend.block.repository.BlogBlockRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 차단 확인(004 FR-146, research B13)의 한 곳. 댓글·방명록 쓰기와 구독이 부른다. 거부는 차단 사실을 드러내지 않는
 * 일반 403 {@code FORBIDDEN}("Write not allowed")이다. 비회원(userId null)은 차단 대상이 아니므로 쿼리 없이 false.
 */
@Component
public class BlogBlockPolicy {

    private final BlogBlockRepository repository;

    public BlogBlockPolicy(BlogBlockRepository repository) {
        this.repository = repository;
    }

    /** 이 회원이 이 블로그에서 차단되었는지. 쿼리 1회(PK), 비회원은 0회. */
    @Transactional(readOnly = true)
    public boolean isBlocked(Long blogId, Long userId) {
        if (blogId == null || userId == null) {
            return false;
        }
        return repository.existsById(new BlogBlockId(blogId, userId));
    }

    /** 차단된 회원이면 403 {@code FORBIDDEN}. 메시지에 차단을 드러내지 않는다. */
    public void requireNotBlocked(Long blogId, Long userId) {
        if (isBlocked(blogId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Write not allowed");
        }
    }
}
