package net.java21.blog.backend.post.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.dto.ArchiveMonthResponse;
import net.java21.blog.backend.post.repository.ArchiveQueryRepository;
import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.StatsProperties;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 월별 보관함(T051, research B12): Asia/Seoul 연·월로 묶어 최근 달부터, 글이 없으면 빈 목록. */
@ExtendWith(MockitoExtension.class)
class ArchiveServiceTest {

    @Mock
    private BlogAccess blogAccess;
    @Mock
    private ArchiveQueryRepository repository;

    private ArchiveService service() {
        return new ArchiveService(blogAccess, repository,
                new BlogCalendar(StatsProperties.defaults(), Clock.systemUTC()));
    }

    @Test
    void groupsByServiceTimeZoneMonthNewestFirst() {
        Blog blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        when(blogAccess.requireVisibleBlog("marco")).thenReturn(blog);
        when(repository.findPublishedTimes(10L)).thenReturn(List.of(
                Instant.parse("2026-11-02T00:00:00Z"),
                Instant.parse("2026-10-15T00:00:00Z"),
                // UTC 9월 30일 15:00 = 한국 10월 1일 00:00
                Instant.parse("2026-09-30T15:00:00Z"),
                Instant.parse("2026-09-30T14:59:59Z"),
                Instant.parse("2025-12-31T00:00:00Z")));

        assertThat(service().archive("marco")).containsExactly(
                new ArchiveMonthResponse(2026, 11, 1),
                new ArchiveMonthResponse(2026, 10, 2),
                new ArchiveMonthResponse(2026, 9, 1),
                new ArchiveMonthResponse(2025, 12, 1));
    }

    @Test
    void emptyBlogAndMissingBlog() {
        Blog blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        when(repository.findPublishedTimes(10L)).thenReturn(List.of());
        assertThat(service().archive(blog)).isEmpty();

        when(blogAccess.requireVisibleBlog("ghost")).thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));
        assertCode(() -> service().archive("ghost"), ErrorCode.BLOG_NOT_FOUND);
    }
}
