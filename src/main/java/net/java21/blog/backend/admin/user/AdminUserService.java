package net.java21.blog.backend.admin.user;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.report.ReportQueryRepository;
import net.java21.blog.backend.admin.user.dto.AdminUserDetail;
import net.java21.blog.backend.admin.user.dto.AdminUserSummary;
import net.java21.blog.backend.admin.user.dto.BlogLimitRequest;
import net.java21.blog.backend.admin.user.dto.BlogLimitResponse;
import net.java21.blog.backend.blog.BlogsProperties;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 회원 관리: 회원별 블로그 한도(001 T161, 006 FR-160, 001 FR-158)와 005 회원 검색·상세(006 FR-104). 정지·해제는
 * {@link SuspensionService}. 관리자 확인은 {@code AdminAccessFilter}가 먼저 한다.
 */
@Service
public class AdminUserService {

    static final String ACTION_BLOG_LIMIT = "USER_BLOG_LIMIT_CHANGE";
    static final String TARGET_USER = AuditActions.TARGET_USER;
    static final int QUERY_MIN = 2;
    static final String FIELD_MAX_BLOGS = "maxBlogs";

    private final UserRepository userRepository;
    private final AdminUserRepository adminUserRepository;
    private final BlogRepository blogRepository;
    private final BlogsProperties blogsProperties;
    private final AdminAuditService auditService;
    private final Clock clock;
    private final AdminUserQueryRepository queryRepository;
    private final ReportQueryRepository reportQueryRepository;
    private final PersonalDataHasher hasher;

    public AdminUserService(UserRepository userRepository, AdminUserRepository adminUserRepository,
            BlogRepository blogRepository, BlogsProperties blogsProperties, AdminAuditService auditService,
            Clock clock, AdminUserQueryRepository queryRepository, ReportQueryRepository reportQueryRepository,
            PersonalDataHasher hasher) {
        this.queryRepository = queryRepository;
        this.reportQueryRepository = reportQueryRepository;
        this.hasher = hasher;
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

    /**
     * 회원 검색(005 research M5, 006 FR-104). {@code by}: {@code email}(정규화 후 해시 정확 일치) / {@code nickname}(앞부분 일치, 기본) /
     * {@code handle}(정확 일치, 삭제된 블로그 포함). {@code q}가 2자 미만이면 400.
     */
    @Transactional(readOnly = true)
    public Page<AdminUserSummary> search(String q, String by, Pageable pageable) {
        String query = q == null ? "" : q.strip();
        if (query.codePointCount(0, query.length()) < QUERY_MIN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Query is too short",
                    List.of(new FieldError("q", "TOO_SHORT", Map.of("min", QUERY_MIN))));
        }
        String mode = by == null || by.isBlank() ? "nickname" : by;
        AdminUserQueryRepository.Search search = switch (mode) {
            case "email" -> new AdminUserQueryRepository.Search(hasher.hashEmail(query), null, null);
            case "handle" -> new AdminUserQueryRepository.Search(null, null, query.toLowerCase(Locale.ROOT));
            case "nickname" -> new AdminUserQueryRepository.Search(null, query, null);
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unknown search mode",
                    List.of(new FieldError("by", "INVALID", Map.of("allowed", List.of("email", "nickname",
                            "handle")))));
        };
        return queryRepository.search(search, pageable);
    }

    /** 회원 상세(쿼리 5회: 회원, 블로그, 글 수, 받은 신고 수, 최근 로그인). 없는 회원 404 {@code USER_NOT_FOUND}. */
    @Transactional(readOnly = true)
    public AdminUserDetail detail(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found: " + userId));
        List<AdminUserDetail.BlogItem> blogs = queryRepository.findBlogs(userId);
        long activeBlogs = blogs.stream().filter(b -> b.status() == BlogStatus.ACTIVE).count();
        Integer maxBlogs = user.getMaxBlogs();
        int limit = maxBlogs == null ? blogsProperties.defaultMaxPerMember() : maxBlogs;
        return new AdminUserDetail(user.getId(), user.getNickname(), user.getStatus(), user.getRole(),
                user.getCreatedAt(), activeBlogs, queryRepository.countPosts(userId),
                reportQueryRepository.countReceivedBy(userId), queryRepository.findLastLoginAt(userId), blogs,
                new AdminUserDetail.BlogLimit(activeBlogs, limit, maxBlogs != null));
    }

    /** {@code {"maxBlogs": n}}. 기본값(null)도 그대로 남긴다. */
    private static Map<String, Object> maxBlogsValue(Integer value) {
        Map<String, Object> map = new HashMap<>();
        map.put(FIELD_MAX_BLOGS, value);
        return map;
    }
}
