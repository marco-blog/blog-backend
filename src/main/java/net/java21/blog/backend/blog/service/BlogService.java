package net.java21.blog.backend.blog.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.blog.BlogsProperties;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.dto.BlogResponse;
import net.java21.blog.backend.blog.dto.CreateBlogRequest;
import net.java21.blog.backend.blog.dto.HandleAvailabilityResponse;
import net.java21.blog.backend.blog.dto.MyBlogsResponse;
import net.java21.blog.backend.blog.dto.UpdateBlogRequest;
import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.category.repository.CategoryQueryRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 만들기·조회·수정·삭제(FR-010~012, FR-158, FR-159).
 * 만들기와 삭제는 한 트랜잭션에서 회원 행을 {@code SELECT ... FOR UPDATE}로 잠근 뒤 ACTIVE 블로그 수를 센다(research R28).
 * 같은 회원의 동시 요청은 이 잠금에서 줄을 서므로 한도를 넘거나 마지막 블로그가 지워지지 않는다.
 */
@Service
public class BlogService {

    private final BlogRepository blogRepository;
    private final BlogQueryRepository blogQueryRepository;
    private final UserRepository userRepository;
    private final BlogAccess blogAccess;
    private final HandlePolicy handlePolicy;
    private final PasswordEncoder passwordEncoder;
    private final BlogsProperties blogsProperties;
    private final CategoryQueryRepository categoryQueryRepository;
    private final Clock clock;

    public BlogService(BlogRepository blogRepository, BlogQueryRepository blogQueryRepository,
            UserRepository userRepository, BlogAccess blogAccess, HandlePolicy handlePolicy,
            PasswordEncoder passwordEncoder, BlogsProperties blogsProperties,
            CategoryQueryRepository categoryQueryRepository, Clock clock) {
        this.blogRepository = blogRepository;
        this.blogQueryRepository = blogQueryRepository;
        this.userRepository = userRepository;
        this.blogAccess = blogAccess;
        this.handlePolicy = handlePolicy;
        this.passwordEncoder = passwordEncoder;
        this.blogsProperties = blogsProperties;
        this.categoryQueryRepository = categoryQueryRepository;
        this.clock = clock;
    }

    /** 주소를 쓸 수 있는지(가입·새 블로그 만들기 화면). 삭제된 블로그의 주소는 TAKEN. */
    @Transactional(readOnly = true)
    public HandleAvailabilityResponse handleAvailability(String handle) {
        HandlePolicy.Violation violation = handlePolicy.violation(handle);
        if (violation == HandlePolicy.Violation.RESERVED) {
            return HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.RESERVED);
        }
        if (violation == HandlePolicy.Violation.INVALID) {
            return HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.INVALID);
        }
        if (blogRepository.existsByHandle(handle)) {
            return HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.TAKEN);
        }
        return HandleAvailabilityResponse.ofAvailable();
    }

    /** 내 블로그(삭제 제외, 만든 순)와 블로그 수·한도. 쿼리 2회(회원, 블로그 목록 + 글 수). */
    @Transactional(readOnly = true)
    public MyBlogsResponse myBlogs(long userId) {
        User user = requireActiveUser(userRepository.findById(userId).orElse(null));
        List<MyBlogsResponse.Item> items = blogQueryRepository.findMyBlogs(userId).stream()
                .map(row -> new MyBlogsResponse.Item(row.handle(), row.title(), null, row.postCount(), row.createdAt()))
                .toList();
        return new MyBlogsResponse(items, items.size(), user.effectiveBlogLimit(blogsProperties.defaultMaxPerMember()));
    }

    @Transactional
    public BlogResponse create(long userId, CreateBlogRequest request) {
        handlePolicy.check(request.handle());
        User user = requireActiveUser(userRepository.findByIdForUpdate(userId).orElse(null));
        long activeBlogs = blogRepository.countByUserIdAndStatus(userId, BlogStatus.ACTIVE);
        if (activeBlogs >= user.effectiveBlogLimit(blogsProperties.defaultMaxPerMember())) {
            throw new BusinessException(ErrorCode.BLOG_LIMIT_EXCEEDED, "Blog limit reached");
        }
        if (blogRepository.existsByHandle(request.handle())) {
            throw new BusinessException(ErrorCode.HANDLE_TAKEN, "Handle taken");
        }
        String title = isBlank(request.title()) ? Blog.defaultTitle(user.getNickname()) : request.title().strip();
        Blog blog = new Blog(user, request.handle(), title);
        try {
            blogRepository.saveAndFlush(blog);
        } catch (DataIntegrityViolationException e) {
            // 다른 회원이 같은 주소를 먼저 저장했다: UNIQUE 제약이 최종 판단한다.
            throw new BusinessException(ErrorCode.HANDLE_TAKEN, "Handle taken");
        }
        return BlogResponse.of(blog);
    }

    /** 블로그와 카테고리 트리(목록 노출 가능 글 수). 쿼리 3회(블로그, 카테고리, 글 수). */
    @Transactional(readOnly = true)
    public BlogResponse get(String handle) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        return BlogResponse.of(blog, categoryQueryRepository.findTree(blog.getId()));
    }

    @Transactional
    public BlogResponse update(long userId, String handle, UpdateBlogRequest request) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        if (request.hasTitle()) {
            if (isBlank(request.getTitle())) {
                throw required("title");
            }
            blog.changeTitle(request.getTitle().strip());
        }
        if (request.hasDescription()) {
            blog.changeDescription(isBlank(request.getDescription()) ? null : request.getDescription());
        }
        if (request.hasCommentEnabled()) {
            if (request.getCommentEnabled() == null) {
                throw required("commentEnabled");
            }
            blog.changeCommentEnabled(request.getCommentEnabled());
        }
        return BlogResponse.of(blog, categoryQueryRepository.findTree(blog.getId()));
    }

    /**
     * 블로그 삭제(FR-159): 비밀번호 확인 → 회원 행 잠금 → ACTIVE 블로그가 2개 이상일 때만 DELETED,
     * 그 블로그의 휴지통에 없는 글은 모두 휴지통으로. 되돌릴 수 없고 주소는 다시 쓸 수 없다.
     */
    @Transactional
    public void delete(long userId, String handle, String password) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        User user = requireActiveUser(userRepository.findByIdForUpdate(userId).orElse(null));
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH, "Password mismatch");
        }
        if (blogRepository.countByUserIdAndStatus(userId, BlogStatus.ACTIVE) < 2) {
            throw new BusinessException(ErrorCode.LAST_BLOG_CANNOT_BE_DELETED, "Cannot delete the last blog");
        }
        Instant now = clock.instant();
        blog.delete(now);
        blogRepository.flush();
        blogQueryRepository.moveAllPostsToTrash(blog.getId(), now);
    }

    private static User requireActiveUser(User user) {
        if (user == null || !user.isActive()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Member is not active");
        }
        return user;
    }

    private static BusinessException required(String field) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                List.of(FieldError.of(field, "REQUIRED")));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
