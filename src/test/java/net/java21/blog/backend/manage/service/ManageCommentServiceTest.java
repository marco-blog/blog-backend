package net.java21.blog.backend.manage.service;

import static net.java21.blog.backend.manage.service.ManagePostServiceTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.comment.repository.BlogCommentRow;
import net.java21.blog.backend.comment.repository.CommentQueryRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.guestbook.service.GuestbookService;
import net.java21.blog.backend.manage.dto.DashboardResponse;
import net.java21.blog.backend.manage.dto.ManageCommentResponse;
import net.java21.blog.backend.manage.repository.ManagePostQueryRepository;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 블로그 관리 댓글(T188, 006 FR-099·100): {@code GET /blogs/{handle}/manage/comments}(주인만, 최신순, postId·postTitle)와
 * 대시보드 {@code newComments7d}(지금부터 7일)·{@code recentComments}(최근 5건).
 */
@ExtendWith(MockitoExtension.class)
class ManageCommentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final BlogCommentRow ROW = new BlogCommentRow(7L, "좋은 글", 2L, "작성자",
            "k3Jd9fQ2xLmA7pZ0bR5tYw", NOW, NOW, 100L, "첫 글");

    @Mock
    private BlogAccess blogAccess;
    @Mock
    private TagQueryRepository tagQueryRepository;
    @Mock
    private CommentQueryRepository repository;
    @Mock
    private ManagePostQueryRepository postRepository;
    @Mock
    private GuestbookService guestbookService;

    private final Blog blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");

    @Test
    void ownerSeesBlogCommentsWithPostTitle() {
        when(blogAccess.requireOwnedActiveBlog("marco", 1L)).thenReturn(blog);
        PageRequest pageable = PageRequest.of(1, 20);
        when(repository.findBlogComments(10L, pageable)).thenReturn(new PageImpl<>(List.of(ROW), pageable, 21));

        Page<ManageCommentResponse> page = service().comments(1L, "marco", pageable);

        assertThat(page.getTotalElements()).isEqualTo(21);
        ManageCommentResponse comment = page.getContent().getFirst();
        assertThat(comment.id()).isEqualTo(7L);
        assertThat(comment.content()).isEqualTo("좋은 글");
        assertThat(comment.deleted()).isFalse();
        assertThat(comment.author().userId()).isEqualTo(2L);
        assertThat(comment.author().nickname()).isEqualTo("작성자");
        assertThat(comment.postId()).isEqualTo(100L);
        assertThat(comment.postTitle()).isEqualTo("첫 글");
    }

    @Test
    void notOwnerIsForbidden() {
        when(blogAccess.requireOwnedActiveBlog("marco", 2L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "not owner"));

        assertCode(() -> service().comments(2L, "marco", PageRequest.of(0, 20)), ErrorCode.FORBIDDEN);
        verify(repository, never()).findBlogComments(anyLong(), any());
    }

    @Test
    void statsCountSevenDaysAndRecentFive() {
        when(repository.countBlogCommentsSince(10L, NOW.minus(Duration.ofDays(7)))).thenReturn(3L);
        when(repository.findRecentBlogComments(10L, 5)).thenReturn(List.of(ROW,
                new BlogCommentRow(8L, "비회원(004)", null, null, null, NOW, NOW, 100L, "첫 글")));

        ManageCommentService.CommentStats stats = service().stats(10L, 5);

        assertThat(stats.newComments7d()).isEqualTo(3);
        assertThat(stats.recentComments()).extracting(ManageCommentResponse::id).containsExactly(7L, 8L);
        assertThat(stats.recentComments().get(1).author()).isNull();
        assertThat(stats.recentComments().get(0).author().profileImageUrl()).isEqualTo("/media/k3Jd9fQ2xLmA7pZ0bR5tYw");
    }

    @Test
    void dashboardCarriesCommentNumbers() {
        when(guestbookService.stats(10L, 5)).thenReturn(new GuestbookService.GuestbookStats(0, List.of()));
        when(blogAccess.requireOwnedActiveBlog("marco", 1L)).thenReturn(blog);
        when(postRepository.countDrafts(10L)).thenReturn(0L);
        when(postRepository.findRecentPosts(10L, 5)).thenReturn(List.of());
        when(repository.countBlogCommentsSince(10L, NOW.minus(Duration.ofDays(7)))).thenReturn(4L);
        when(repository.findRecentBlogComments(10L, 5)).thenReturn(List.of(ROW));

        DashboardResponse dashboard = new ManageDashboardService(blogAccess, postRepository, tagQueryRepository,
                new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 500), service(), guestbookService)
                .dashboard(1L, "marco");

        assertThat(dashboard.newComments7d()).isEqualTo(4);
        assertThat(dashboard.recentComments()).singleElement()
                .satisfies(c -> assertThat(c.postTitle()).isEqualTo("첫 글"));
    }

    private ManageCommentService service() {
        return new ManageCommentService(blogAccess, repository, new MutableClock(NOW));
    }
}
