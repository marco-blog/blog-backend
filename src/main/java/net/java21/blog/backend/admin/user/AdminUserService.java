package net.java21.blog.backend.admin.user;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.user.dto.BlogLimitRequest;
import net.java21.blog.backend.admin.user.dto.BlogLimitResponse;
import net.java21.blog.backend.blog.BlogsProperties;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 회원 관리 중 001 범위: 회원별 블로그 한도(T161, 006 FR-160, 001 FR-158). 관리자 확인은 {@code AdminAccessFilter}가 먼저 한다.
 */
@Service
public class AdminUserService {

    static final String ACTION_BLOG_LIMIT = "USER_BLOG_LIMIT_CHANGE";
    static final String TARGET_USER = "USER";
    static final String FIELD_MAX_BLOGS = "maxBlogs";

    private final UserRepository userRepository;
    private final AdminUserRepository adminUserRepository;
    private final BlogRepository blogRepository;
    private final BlogsProperties blogsProperties;
    private final AdminAuditService auditService;
    private final Clock clock;

    public AdminUserService(UserRepository userRepository, AdminUserRepository adminUserRepository,
            BlogRepository blogRepository, BlogsProperties blogsProperties, AdminAuditService auditService,
            Clock clock) {
        this.userRepository = userRepository;
        this.adminUserRepository = adminUserRepository;
        this.blogRepository = blogRepository;
        this.blogsProperties = blogsProperties;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 회원별 블로그 한도를 바꾼다({@code null}=기본값). 지금 가진 블로그보다 낮춰도 허용한다(기존 블로그는 그대로, 새로 만들기만 막힘).
     * 블로그 만들기와 같은 회원 행 잠금({@code SELECT ... FOR UPDATE}, research R28) 안에서 바꾸고, 값이 바뀌었으면 작업 기록에
     * 변경 전후 값({@code maxBlogs}만)을 남긴다. 없는 회원은 404 {@code NOT_FOUND}.
     */
    @Transactional
    public BlogLimitResponse changeBlogLimit(long adminId, long userId, BlogLimitRequest request, String requestIp) {
        if (!request.hasMaxBlogs()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "maxBlogs is required",
                    List.of(new FieldError(FIELD_MAX_BLOGS, "REQUIRED", Map.of())));
        }
        Integer maxBlogs = request.getMaxBlogs();
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found: " + userId));
        Integer before = user.getMaxBlogs();
        if (!Objects.equals(before, maxBlogs)) {
            adminUserRepository.updateMaxBlogs(userId, maxBlogs, clock.instant());
            auditService.record(adminId, ACTION_BLOG_LIMIT, TARGET_USER, userId, maxBlogsValue(before),
                    maxBlogsValue(maxBlogs), requestIp);
        }
        long blogCount = blogRepository.countByUserIdAndStatus(userId, BlogStatus.ACTIVE);
        int effectiveLimit = maxBlogs == null ? blogsProperties.defaultMaxPerMember() : maxBlogs;
        return new BlogLimitResponse(userId, blogCount, maxBlogs, effectiveLimit);
    }

    /** {@code {"maxBlogs": n}}. 기본값(null)도 그대로 남긴다. */
    private static Map<String, Object> maxBlogsValue(Integer value) {
        Map<String, Object> map = new HashMap<>();
        map.put(FIELD_MAX_BLOGS, value);
        return map;
    }
}
