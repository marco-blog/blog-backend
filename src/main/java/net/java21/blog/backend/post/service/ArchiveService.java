package net.java21.blog.backend.post.service;

import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.post.dto.ArchiveMonthResponse;
import net.java21.blog.backend.post.repository.ArchiveQueryRepository;
import net.java21.blog.backend.stats.BlogCalendar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 월별 보관함(004 FR-061): 목록 노출 가능 글이 있는 달만, 최근 달부터, 기준 시간대의 연·월. */
@Service
public class ArchiveService {

    private final BlogAccess blogAccess;
    private final ArchiveQueryRepository repository;
    private final BlogCalendar calendar;

    public ArchiveService(BlogAccess blogAccess, ArchiveQueryRepository repository, BlogCalendar calendar) {
        this.blogAccess = blogAccess;
        this.repository = repository;
        this.calendar = calendar;
    }

    /** 볼 수 있는 블로그의 보관함. 쿼리 2회(블로그, 발행 시각). */
    @Transactional(readOnly = true)
    public List<ArchiveMonthResponse> archive(String handle) {
        return archive(blogAccess.requireVisibleBlog(handle));
    }

    /** 이미 확인한 블로그의 보관함(사이드바). 쿼리 1회. */
    @Transactional(readOnly = true)
    public List<ArchiveMonthResponse> archive(Blog blog) {
        Map<YearMonth, Long> counts = new LinkedHashMap<>();
        for (Instant publishedAt : repository.findPublishedTimes(blog.getId())) {
            counts.merge(calendar.monthOf(publishedAt), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(e -> new ArchiveMonthResponse(e.getKey().getYear(), e.getKey().getMonthValue(), e.getValue()))
                .toList();
    }
}
