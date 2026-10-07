package net.java21.blog.backend.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.report.ReportQueryRepository;
import net.java21.blog.backend.admin.user.dto.AdminUserDetail;
import net.java21.blog.backend.admin.user.dto.AdminUserSummary;
import net.java21.blog.backend.admin.user.dto.BlogLimitRequest;
import net.java21.blog.backend.admin.user.dto.BlogLimitResponse;
import net.java21.blog.backend.blog.BlogsProperties;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 회원별 블로그 한도(T153, 006 FR-106·160, quickstart #33): {@code { maxBlogs }}는 0 이상 또는 null(기본값), 블로그 수보다 낮춰도
 * 허용, 응답 {@code { userId, blogCount, maxBlogs, effectiveLimit }}, 작업 기록에 변경 전후 값(개인정보 평문 없이).
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final long ADMIN = 1L;
    private static final long MARCO = 7L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminUserRepository adminUserRepository;
    @Mock
    private BlogRepository blogRepository;
    @Mock
    private AdminAuditService auditService;
    @Mock
    private AdminUserQueryRepository queryRepository;
    @Mock
    private ReportQueryRepository reportQueryRepository;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepository, adminUserRepository, blogRepository, new BlogsProperties(3),
                auditService, Clock.fixed(NOW, ZoneOffset.UTC), queryRepository, reportQueryRepository,
                TestEntities.HASHER);
    }

    @Test
    void lowerThanCurrentBlogCountIsAllowedAndAudited() {
        when(userRepository.findByIdForUpdate(MARCO)).thenReturn(Optional.of(TestEntities.user(MARCO)));
        when(blogRepository.countByUserIdAndStatus(MARCO, BlogStatus.ACTIVE)).thenReturn(2L);

        BlogLimitResponse response = service.changeBlogLimit(ADMIN, MARCO, BlogLimitRequest.of(0), "203.0.113.9");

        assertThat(response).isEqualTo(new BlogLimitResponse(MARCO, 2, 0, 0));
        verify(adminUserRepository).updateMaxBlogs(MARCO, 0, NOW);
        verify(auditService).record(ADMIN, "USER_BLOG_LIMIT_CHANGE", "USER", MARCO, value(null), value(0),
                "203.0.113.9");
    }

    @Test
    void nullRestoresTheDefault() {
        User marco = TestEntities.with(TestEntities.user(MARCO), "maxBlogs", 0);
        when(userRepository.findByIdForUpdate(MARCO)).thenReturn(Optional.of(marco));
        when(blogRepository.countByUserIdAndStatus(MARCO, BlogStatus.ACTIVE)).thenReturn(1L);

        BlogLimitResponse response = service.changeBlogLimit(ADMIN, MARCO, BlogLimitRequest.of(null), "::1");

        assertThat(response).isEqualTo(new BlogLimitResponse(MARCO, 1, null, 3));
        verify(adminUserRepository).updateMaxBlogs(MARCO, null, NOW);
        verify(auditService).record(ADMIN, "USER_BLOG_LIMIT_CHANGE", "USER", MARCO, value(0), value(null), "::1");
    }

    @Test
    void sameValueChangesNothingAndLeavesNoRecord() {
        User marco = TestEntities.with(TestEntities.user(MARCO), "maxBlogs", 5);
        when(userRepository.findByIdForUpdate(MARCO)).thenReturn(Optional.of(marco));
        when(blogRepository.countByUserIdAndStatus(MARCO, BlogStatus.ACTIVE)).thenReturn(4L);

        assertThat(service.changeBlogLimit(ADMIN, MARCO, BlogLimitRequest.of(5), "::1"))
                .isEqualTo(new BlogLimitResponse(MARCO, 4, 5, 5));
        verify(adminUserRepository, never()).updateMaxBlogs(anyLong(), any(), any());
        verify(auditService, never()).record(anyLong(), anyString(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    void unknownMemberIs404() {
        when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeBlogLimit(ADMIN, 99L, BlogLimitRequest.of(1), "::1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(auditService, never()).record(anyLong(), anyString(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    void missingFieldIsRequired() {
        assertThatThrownBy(() -> service.changeBlogLimit(ADMIN, MARCO, new BlogLimitRequest(), "::1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement()
                            .satisfies(f -> assertThat(f.field()).isEqualTo("maxBlogs"))
                            .satisfies(f -> assertThat(f.code()).isEqualTo("REQUIRED"));
                });
        verify(userRepository, never()).findByIdForUpdate(eq(MARCO));
    }

    @Test
    void searchByEmailUsesTheNormalizedHash() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<AdminUserSummary> page = new PageImpl<>(List.of());
        String hash = TestEntities.HASHER.hashEmail(" Marco@Example.com ".strip());
        when(queryRepository.search(new AdminUserQueryRepository.Search(hash, null, null), pageable)).thenReturn(page);

        assertThat(service.search(" Marco@Example.com ", "email", pageable)).isSameAs(page);
    }

    @Test
    void searchDefaultsToNicknamePrefixAndLowercasesHandles() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<AdminUserSummary> page = new PageImpl<>(List.of());
        when(queryRepository.search(any(), eq(pageable))).thenReturn(page);

        service.search("마르코", null, pageable);
        verify(queryRepository).search(new AdminUserQueryRepository.Search(null, "마르코", null), pageable);
        service.search("Marco", "handle", pageable);
        verify(queryRepository).search(new AdminUserQueryRepository.Search(null, null, "marco"), pageable);
    }

    @Test
    void searchRejectsShortQueriesAndUnknownModes() {
        Pageable pageable = PageRequest.of(0, 20);
        assertThatThrownBy(() -> service.search(" a ", "nickname", pageable))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors()).singleElement()
                        .satisfies(f -> assertThat(f.code()).isEqualTo("TOO_SHORT")));
        assertThatThrownBy(() -> service.search(null, "nickname", pageable)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.search("marco", "phone", pageable))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors()).singleElement()
                        .satisfies(f -> assertThat(f.field()).isEqualTo("by")));
    }

    @Test
    void detailCountsActiveBlogsAndUsesTheDefaultLimit() {
        when(userRepository.findById(MARCO)).thenReturn(Optional.of(TestEntities.user(MARCO)));
        List<AdminUserDetail.BlogItem> blogs = List.of(new AdminUserDetail.BlogItem("marco", "Marco", BlogStatus.ACTIVE),
                new AdminUserDetail.BlogItem("old", "Old", BlogStatus.DELETED));
        when(queryRepository.findBlogs(MARCO)).thenReturn(blogs);
        when(queryRepository.countPosts(MARCO)).thenReturn(12L);
        when(reportQueryRepository.countReceivedBy(MARCO)).thenReturn(3L);
        when(queryRepository.findLastLoginAt(MARCO)).thenReturn(NOW);

        AdminUserDetail detail = service.detail(MARCO);

        assertThat(detail.id()).isEqualTo(MARCO);
        assertThat(detail.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(detail.blogCount()).isEqualTo(1);
        assertThat(detail.postCount()).isEqualTo(12);
        assertThat(detail.receivedReportCount()).isEqualTo(3);
        assertThat(detail.lastLoginAt()).isEqualTo(NOW);
        assertThat(detail.blogs()).hasSize(2);
        assertThat(detail.blogLimit()).isEqualTo(new AdminUserDetail.BlogLimit(1, 3, false));
    }

    @Test
    void detailShowsACustomLimitAndUnknownUsersAre404() {
        when(userRepository.findById(MARCO))
                .thenReturn(Optional.of(TestEntities.with(TestEntities.user(MARCO), "maxBlogs", 5)));
        when(queryRepository.findBlogs(MARCO)).thenReturn(List.of());
        assertThat(service.detail(MARCO).blogLimit()).isEqualTo(new AdminUserDetail.BlogLimit(0, 5, true));

        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(99L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    private static Map<String, Object> value(Integer maxBlogs) {
        Map<String, Object> map = new HashMap<>();
        map.put("maxBlogs", maxBlogs);
        return map;
    }
}
