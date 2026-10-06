package net.java21.blog.backend.manage.service;

import static net.java21.blog.backend.manage.service.ManagePostServiceTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.manage.dto.DashboardResponse;
import net.java21.blog.backend.manage.repository.ManagePostQueryRepository;
import net.java21.blog.backend.manage.repository.ManagePostRow;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 블로그 관리 대시보드(T150, 006 FR-100): 임시저장 수, 최근 글 5편. 댓글 수치는 US3에서 채운다. */
@ExtendWith(MockitoExtension.class)
class ManageDashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private BlogAccess blogAccess;
    @Mock
    private ManagePostQueryRepository repository;
    @Mock
    private TagQueryRepository tagQueryRepository;

    @Test
    void draftCountAndFiveRecentPosts() {
        Blog blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        when(blogAccess.requireOwnedActiveBlog("marco", 1L)).thenReturn(blog);
        when(repository.countDrafts(10L)).thenReturn(2L);
        when(repository.findRecentPosts(10L, ManageDashboardService.RECENT_SIZE)).thenReturn(List.of(
                new ManagePostRow(3L, "최근 글", "요약", null, 7L, "Spring", 1, 0, PostVisibility.PUBLIC, PostStatus.PUBLISHED, NOW,
                        NOW, false, null)));

        when(tagQueryRepository.findTagNames(List.of(3L))).thenReturn(Map.of(3L, List.of("spring")));

        DashboardResponse dashboard = service().dashboard(1L, "marco");

        assertThat(ManageDashboardService.RECENT_SIZE).isEqualTo(5);
        assertThat(dashboard.draftCount()).isEqualTo(2);
        assertThat(dashboard.recentPosts()).singleElement()
                .satisfies(p -> assertThat(p.title()).isEqualTo("최근 글"))
                .satisfies(p -> assertThat(p.purgeAt()).isNull())
                .satisfies(p -> assertThat(p.tags()).containsExactly("spring"))
                .satisfies(p -> assertThat(p.category().name()).isEqualTo("Spring"));
        assertThat(dashboard.newComments7d()).isZero();
        assertThat(dashboard.recentComments()).isEmpty();
    }

    @Test
    void notOwnerIsForbidden() {
        when(blogAccess.requireOwnedActiveBlog("marco", 2L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "not owner"));

        assertCode(() -> service().dashboard(2L, "marco"), ErrorCode.FORBIDDEN);
        verify(repository, never()).countDrafts(anyLong());
    }

    private ManageDashboardService service() {
        return new ManageDashboardService(blogAccess, repository, tagQueryRepository,
                new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 500));
    }
}
