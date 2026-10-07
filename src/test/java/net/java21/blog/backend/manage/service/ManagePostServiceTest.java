package net.java21.blog.backend.manage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.manage.dto.BulkAction;
import net.java21.blog.backend.manage.dto.BulkPostRequest;
import net.java21.blog.backend.manage.dto.ManagePostFilter;
import net.java21.blog.backend.manage.repository.ManagePostQueryRepository;
import net.java21.blog.backend.manage.repository.ManagePostRow;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.CategoryRef;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** 글 관리 목록·일괄 작업(T149, 006 FR-101): 최대 100개, 하나라도 그 블로그 글이 아니면 403, 결과 {@code { updated: n }}. */
@ExtendWith(MockitoExtension.class)
class ManagePostServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final long OWNER = 1L;

    @Mock
    private BlogAccess blogAccess;
    @Mock
    private ManagePostQueryRepository repository;
    @Mock
    private CategoryAccess categoryAccess;
    @Mock
    private TagQueryRepository tagQueryRepository;

    private ManagePostService service;
    private Blog blog;

    @BeforeEach
    void setUp() {
        service = new ManagePostService(blogAccess, repository, categoryAccess, tagQueryRepository,
                new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 500), Clock.fixed(NOW, ZoneOffset.UTC));
        blog = TestEntities.blog(10L, TestEntities.user(OWNER), "marco");
    }

    @Test
    void listIsOwnerOnlyAndAddsPurgeAtToTrash() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);
        ManagePostFilter trash = new ManagePostFilter(PostStatus.DELETED, null, null, null);
        Instant deletedAt = NOW.minus(Duration.ofDays(2));
        ManagePostRow row = new ManagePostRow(5L, "버린 글", "요약", null, 7L, "Spring", 3, 0, PostVisibility.PRIVATE,
                PostStatus.DELETED, NOW.minus(Duration.ofDays(9)), deletedAt, false, deletedAt, false, null);
        when(repository.findPosts(eq(10L), eq(trash), eq(NOW.minus(Duration.ofDays(30))), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));
        when(tagQueryRepository.findTagNames(List.of(5L))).thenReturn(Map.of(5L, List.of("jpa", "spring")));

        Page<PostSummaryResponse> page = service.posts(OWNER, "marco", trash, PageRequest.of(0, 20));

        PostSummaryResponse item = page.getContent().getFirst();
        assertThat(item.deletedAt()).isEqualTo(deletedAt);
        assertThat(item.purgeAt()).isEqualTo(deletedAt.plus(Duration.ofDays(30)));
        assertThat(item.visibility()).isEqualTo(PostVisibility.PRIVATE);
        assertThat(item.tags()).containsExactly("jpa", "spring");
        assertThat(item.category()).isEqualTo(new CategoryRef(7L, "Spring"));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void liveListHasNoPurgeAt() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);
        ManagePostRow row = new ManagePostRow(5L, "글", null, null, null, null, 0, 0, PostVisibility.PUBLIC,
                PostStatus.DRAFT, null, NOW, true, null, false, null);
        when(repository.findPosts(eq(10L), eq(ManagePostFilter.ALL), any(), any()))
                .thenReturn(new PageImpl<>(List.of(row)));

        PostSummaryResponse item = service.posts(OWNER, "marco", ManagePostFilter.ALL, PageRequest.of(0, 20))
                .getContent().getFirst();

        assertThat(item.hasDraft()).isTrue();
        assertThat(item.category()).isNull();
        assertThat(item.tags()).isEmpty();
        assertThat(item.deletedAt()).isNull();
        assertThat(item.purgeAt()).isNull();
    }

    @Test
    void ownerCheckFailuresPropagate() {
        when(blogAccess.requireOwnedActiveBlog("marco", 2L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "not owner"));

        assertCode(() -> service.posts(2L, "marco", ManagePostFilter.ALL, PageRequest.of(0, 20)),
                ErrorCode.FORBIDDEN);
        assertCode(() -> service.bulk(2L, "marco", new BulkPostRequest(List.of(1L), BulkAction.DELETE, null, null)),
                ErrorCode.FORBIDDEN);
        verify(repository, never()).moveToTrash(anyLong(), anyCollection(), any());
    }

    @Test
    void changeVisibilityUpdatesAllOwnedPostsOnce() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);
        when(repository.countOwned(10L, List.of(1L, 2L, 3L))).thenReturn(3L);
        when(repository.changeVisibility(10L, List.of(1L, 2L, 3L), PostVisibility.PRIVATE, NOW)).thenReturn(3L);

        var result = service.bulk(OWNER, "marco",
                new BulkPostRequest(List.of(1L, 2L, 3L, 2L), BulkAction.CHANGE_VISIBILITY, PostVisibility.PRIVATE, null));

        assertThat(result.updated()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
    }

    /** 005 T033: 숨긴 글은 바뀌지 않고 {@code skipped}로 센다. */
    @Test
    void hiddenPostsAreReportedAsSkipped() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);
        when(repository.countOwned(10L, List.of(1L, 2L))).thenReturn(2L);
        when(repository.countHidden(10L, List.of(1L, 2L))).thenReturn(1L);
        when(repository.changeVisibility(10L, List.of(1L, 2L), PostVisibility.PRIVATE, NOW)).thenReturn(1L);
        when(repository.changeNotice(10L, List.of(1L, 2L), true)).thenReturn(1L);

        var visibility = service.bulk(OWNER, "marco",
                new BulkPostRequest(List.of(1L, 2L), BulkAction.CHANGE_VISIBILITY, PostVisibility.PRIVATE, null));
        assertThat(visibility.updated()).isEqualTo(1);
        assertThat(visibility.skipped()).isEqualTo(1);
        var notice = service.bulk(OWNER, "marco", new BulkPostRequest(List.of(1L, 2L), BulkAction.NOTICE, null,
                null));
        assertThat(notice.skipped()).isEqualTo(1);
    }

    /** 004 결정 26: 일괄 작업으로 PROTECTED는 지정할 수 없다(비밀번호가 없음). */
    @Test
    void changeVisibilityToProtectedIsInvalid() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);

        assertThatThrownBy(() -> service.bulk(OWNER, "marco",
                new BulkPostRequest(List.of(1L), BulkAction.CHANGE_VISIBILITY, PostVisibility.PROTECTED, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement()
                            .satisfies(f -> assertThat(f.field()).isEqualTo("visibility"))
                            .satisfies(f -> assertThat(f.code()).isEqualTo("INVALID"));
                });
        verify(repository, never()).changeVisibility(anyLong(), anyCollection(), any(), any());
    }

    @Test
    void changeVisibilityNeedsVisibility() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);

        assertThatThrownBy(() -> service.bulk(OWNER, "marco",
                new BulkPostRequest(List.of(1L), BulkAction.CHANGE_VISIBILITY, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement()
                            .satisfies(f -> assertThat(f.field()).isEqualTo("visibility"))
                            .satisfies(f -> assertThat(f.code()).isEqualTo("REQUIRED"));
                });
        verify(repository, never()).countOwned(anyLong(), anyCollection());
    }

    @Test
    void deleteMovesOwnedPostsToTrash() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);
        when(repository.countOwned(10L, List.of(4L, 5L))).thenReturn(2L);
        when(repository.moveToTrash(10L, List.of(4L, 5L), NOW)).thenReturn(1L);

        assertThat(service.bulk(OWNER, "marco", new BulkPostRequest(List.of(4L, 5L), BulkAction.DELETE, null, null))
                .updated()).isEqualTo(1);
    }

    /** 004 T050: 공지 지정·해제(휴지통 글은 저장소 쿼리가 건너뜀). */
    @Test
    void noticeAndUnnoticeChangeOwnedPosts() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);
        when(repository.countOwned(10L, List.of(4L, 5L))).thenReturn(2L);
        when(repository.changeNotice(10L, List.of(4L, 5L), true)).thenReturn(1L);
        when(repository.changeNotice(10L, List.of(4L, 5L), false)).thenReturn(2L);

        assertThat(service.bulk(OWNER, "marco", new BulkPostRequest(List.of(4L, 5L, 4L), BulkAction.NOTICE, null,
                null)).updated()).isEqualTo(1);
        assertThat(service.bulk(OWNER, "marco", new BulkPostRequest(List.of(4L, 5L), BulkAction.UNNOTICE, null,
                null)).updated()).isEqualTo(2);
    }

    @Test
    void anyPostOfAnotherBlogIsForbiddenAndNothingChanges() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);
        when(repository.countOwned(10L, List.of(1L, 99L))).thenReturn(1L);

        assertCode(() -> service.bulk(OWNER, "marco",
                new BulkPostRequest(List.of(1L, 99L), BulkAction.DELETE, null, null)), ErrorCode.FORBIDDEN);
        assertCode(() -> service.bulk(OWNER, "marco",
                new BulkPostRequest(List.of(1L, 99L), BulkAction.CHANGE_VISIBILITY, PostVisibility.PUBLIC, null)),
                ErrorCode.FORBIDDEN);
        verify(repository, never()).moveToTrash(anyLong(), anyCollection(), any());
        verify(repository, never()).changeVisibility(anyLong(), anyCollection(), any(), any());
    }

    @Test
    void moreThanHundredDistinctPostsIsRejected() {
        when(blogAccess.requireOwnedActiveBlog("marco", OWNER)).thenReturn(blog);
        List<Long> ids = LongStream.rangeClosed(1, 101).boxed().toList();

        assertCode(() -> service.bulk(OWNER, "marco", new BulkPostRequest(ids, BulkAction.DELETE, null, null)),
                ErrorCode.VALIDATION_FAILED);
        verify(repository, never()).countOwned(anyLong(), anyCollection());
    }

    static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(code);
    }
}
