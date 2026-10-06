package net.java21.blog.backend.blog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.blog.BlogsProperties;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.dto.BlogResponse;
import net.java21.blog.backend.category.dto.CategoryNode;
import net.java21.blog.backend.category.repository.CategoryQueryRepository;
import net.java21.blog.backend.blog.dto.CreateBlogRequest;
import net.java21.blog.backend.blog.dto.HandleAvailabilityResponse;
import net.java21.blog.backend.blog.dto.MyBlogsResponse;
import net.java21.blog.backend.blog.dto.UpdateBlogRequest;
import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.repository.MyBlogRow;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 블로그 만들기·조회·수정·삭제(T055, FR-010~012, FR-158·159, AS17~19). */
@ExtendWith(MockitoExtension.class)
class BlogServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private BlogRepository blogRepository;
    @Mock
    private BlogQueryRepository blogQueryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private CategoryQueryRepository categoryQueryRepository;

    private BlogService service;
    private User owner;

    @BeforeEach
    void setUp() {
        BlogAccess access = new BlogAccess(blogRepository);
        service = new BlogService(blogRepository, blogQueryRepository, userRepository, access, new HandlePolicy(),
                passwordEncoder, new BlogsProperties(3), categoryQueryRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        owner = TestEntities.user(1L, "marco@example.com", "$2a$hash", "마르코");
    }

    // ---- 만들기 ----

    @Test
    void createLocksMemberRowThenCountsThenInserts() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(2L);

        BlogResponse response = service.create(1L, new CreateBlogRequest("marco-dev", " 개발 노트 "));

        assertThat(response.handle()).isEqualTo("marco-dev");
        assertThat(response.title()).isEqualTo("개발 노트");
        assertThat(response.owner().nickname()).isEqualTo("마르코");
        assertThat(response.categories()).isEmpty();
        InOrder order = inOrder(userRepository, blogRepository);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(blogRepository).countByUserIdAndStatus(1L, BlogStatus.ACTIVE);
        order.verify(blogRepository).saveAndFlush(any(Blog.class));
    }

    @Test
    void createWithoutTitleUsesNicknameDefault() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        ArgumentCaptor<Blog> saved = ArgumentCaptor.forClass(Blog.class);

        service.create(1L, new CreateBlogRequest("marco-dev", null));

        verify(blogRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("마르코의 블로그");
        assertThat(saved.getValue().getUser()).isSameAs(owner);
        assertThat(saved.getValue().isCommentEnabled()).isTrue();
        assertThat(saved.getValue().getStatus()).isEqualTo(BlogStatus.ACTIVE);
    }

    @Test
    void createRejectedAtDefaultLimit() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(3L);

        expect(() -> service.create(1L, new CreateBlogRequest("marco-x", null)), ErrorCode.BLOG_LIMIT_EXCEEDED);
        verify(blogRepository, never()).saveAndFlush(any());
    }

    @Test
    void memberLimitOverridesDefault() {
        TestEntities.with(owner, "maxBlogs", 0);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(1L);
        expect(() -> service.create(1L, new CreateBlogRequest("marco-x", null)), ErrorCode.BLOG_LIMIT_EXCEEDED);

        TestEntities.with(owner, "maxBlogs", 5);
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(4L);
        assertThat(service.create(1L, new CreateBlogRequest("marco-x", null)).handle()).isEqualTo("marco-x");
    }

    @Test
    void createChecksHandleRulesBeforeLocking() {
        expect(() -> service.create(1L, new CreateBlogRequest("settings", null)), ErrorCode.HANDLE_RESERVED);
        expect(() -> service.create(1L, new CreateBlogRequest("x", null)), ErrorCode.HANDLE_INVALID);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void createRejectsTakenHandleIncludingDeletedBlogs() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.existsByHandle("marco-life")).thenReturn(true);
        expect(() -> service.create(1L, new CreateBlogRequest("marco-life", null)), ErrorCode.HANDLE_TAKEN);
    }

    @Test
    void createMapsUniqueViolationToHandleTaken() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.saveAndFlush(any(Blog.class))).thenThrow(new DataIntegrityViolationException("dup"));
        expect(() -> service.create(1L, new CreateBlogRequest("marco-life", null)), ErrorCode.HANDLE_TAKEN);
    }

    @Test
    void inactiveOrMissingMemberCannotCreate() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        expect(() -> service.create(1L, new CreateBlogRequest("marco-x", null)), ErrorCode.UNAUTHENTICATED);
        TestEntities.with(owner, "status", UserStatus.SUSPENDED);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        expect(() -> service.create(1L, new CreateBlogRequest("marco-x", null)), ErrorCode.UNAUTHENTICATED);
    }

    // ---- 조회 ----

    @Test
    void getReturnsVisibleBlogWithOwner() {
        Blog blog = TestEntities.blog(10L, TestEntities.with(owner, "bio", "자바 개발자"), "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        List<CategoryNode> tree = List.of(new CategoryNode(1L, "Spring", 3,
                List.of(new CategoryNode(2L, "Boot", 2, List.of()))));
        when(categoryQueryRepository.findTree(10L)).thenReturn(tree);

        BlogResponse response = service.get("marco");

        assertThat(response.handle()).isEqualTo("marco");
        assertThat(response.title()).isEqualTo("마르코의 블로그");
        assertThat(response.description()).isNull();
        assertThat(response.coverImageUrl()).isNull();
        assertThat(response.commentEnabled()).isTrue();
        assertThat(response.owner()).isEqualTo(new BlogResponse.Owner("마르코", null, "자바 개발자"));
        assertThat(response.categories()).isEqualTo(tree);
    }

    @Test
    void missingOrDeletedBlogIsNotFound() {
        when(blogRepository.findByHandleWithOwner("nope")).thenReturn(Optional.empty());
        expect(() -> service.get("nope"), ErrorCode.BLOG_NOT_FOUND);

        Blog deleted = TestEntities.blog(10L, owner, "gone");
        deleted.delete(NOW);
        when(blogRepository.findByHandleWithOwner("gone")).thenReturn(Optional.of(deleted));
        expect(() -> service.get("gone"), ErrorCode.BLOG_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void blogOfSuspendedOrWithdrawnMemberIsNotFoundEvenForOwner(UserStatus status) {
        TestEntities.with(owner, "status", status);
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(TestEntities.blog(10L, owner, "marco")));
        expect(() -> service.get("marco"), ErrorCode.BLOG_NOT_FOUND);
        expect(() -> service.update(1L, "marco", new UpdateBlogRequest()), ErrorCode.BLOG_NOT_FOUND);
    }

    @Test
    void myBlogsReturnsItemsCountAndEffectiveLimit() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        when(blogQueryRepository.findMyBlogs(1L)).thenReturn(List.of(
                new MyBlogRow(10L, "marco", "첫째", null, 3, created),
                new MyBlogRow(11L, "marco-dev", "둘째", 99L, 0, created)));

        MyBlogsResponse response = service.myBlogs(1L);

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.limit()).isEqualTo(3);
        assertThat(response.items()).containsExactly(
                new MyBlogsResponse.Item("marco", "첫째", null, 3, created),
                new MyBlogsResponse.Item("marco-dev", "둘째", null, 0, created));

        TestEntities.with(owner, "maxBlogs", 1);
        assertThat(service.myBlogs(1L).limit()).isEqualTo(1);
    }

    @Test
    void handleAvailability() {
        when(blogRepository.existsByHandle("marco")).thenReturn(true);
        assertThat(service.handleAvailability("marco"))
                .isEqualTo(HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.TAKEN));
        assertThat(service.handleAvailability("Admin"))
                .isEqualTo(HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.RESERVED));
        assertThat(service.handleAvailability("a"))
                .isEqualTo(HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.INVALID));
        assertThat(service.handleAvailability("free-name")).isEqualTo(HandleAvailabilityResponse.ofAvailable());
    }

    // ---- 수정 ----

    @Test
    void patchChangesOnlySentFields() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        blog.changeDescription("원래 소개");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));

        UpdateBlogRequest request = new UpdateBlogRequest();
        request.setTitle(" 새 제목 ");
        request.setCommentEnabled(false);
        BlogResponse response = service.update(1L, "marco", request);

        assertThat(response.title()).isEqualTo("새 제목");
        assertThat(response.description()).isEqualTo("원래 소개");
        assertThat(response.commentEnabled()).isFalse();

        UpdateBlogRequest clear = new UpdateBlogRequest();
        clear.setDescription(null);
        assertThat(service.update(1L, "marco", clear).description()).isNull();
    }

    @Test
    void patchCannotClearTitleOrCommentSetting() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(TestEntities.blog(10L, owner, "marco")));
        UpdateBlogRequest title = new UpdateBlogRequest();
        title.setTitle(null);
        expectField(() -> service.update(1L, "marco", title), "title");
        UpdateBlogRequest comment = new UpdateBlogRequest();
        comment.setCommentEnabled(null);
        expectField(() -> service.update(1L, "marco", comment), "commentEnabled");
    }

    @Test
    void onlyOwnerCanPatch() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(TestEntities.blog(10L, owner, "marco")));
        expect(() -> service.update(2L, "marco", new UpdateBlogRequest()), ErrorCode.FORBIDDEN);
    }

    // ---- 삭제 ----

    @Test
    void deleteMarksBlogDeletedAndMovesPostsToTrashInsideMemberLock() {
        Blog blog = TestEntities.blog(10L, owner, "marco-life");
        when(blogRepository.findByHandleWithOwner("marco-life")).thenReturn(Optional.of(blog));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches("password1", "$2a$hash")).thenReturn(true);
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(2L);

        service.delete(1L, "marco-life", "password1");

        assertThat(blog.getStatus()).isEqualTo(BlogStatus.DELETED);
        assertThat(blog.getDeletedAt()).isEqualTo(NOW);
        InOrder order = inOrder(userRepository, blogRepository, blogQueryRepository);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(blogRepository).countByUserIdAndStatus(1L, BlogStatus.ACTIVE);
        order.verify(blogQueryRepository).moveAllPostsToTrash(10L, NOW);
    }

    @Test
    void lastBlogCannotBeDeleted() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches("password1", "$2a$hash")).thenReturn(true);
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(1L);

        expect(() -> service.delete(1L, "marco", "password1"), ErrorCode.LAST_BLOG_CANNOT_BE_DELETED);
        assertThat(blog.isActive()).isTrue();
        verify(blogQueryRepository, never()).moveAllPostsToTrash(anyLong(), any());
    }

    @Test
    void wrongPasswordIsCurrentPasswordMismatch() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        expect(() -> service.delete(1L, "marco", "wrong"), ErrorCode.CURRENT_PASSWORD_MISMATCH);
        expect(() -> service.delete(1L, "marco", null), ErrorCode.CURRENT_PASSWORD_MISMATCH);
        assertThat(blog.isActive()).isTrue();
    }

    @Test
    void onlyOwnerCanDeleteAndDeletedBlogIsNotFound() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        expect(() -> service.delete(2L, "marco", "password1"), ErrorCode.FORBIDDEN);

        blog.delete(NOW);
        expect(() -> service.delete(1L, "marco", "password1"), ErrorCode.BLOG_NOT_FOUND);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    private static void expect(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private static void expectField(Runnable action, String field) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly(field);
                });
    }
}
