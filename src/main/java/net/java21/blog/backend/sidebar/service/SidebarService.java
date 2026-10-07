package net.java21.blog.backend.sidebar.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.service.ArchiveService;
import net.java21.blog.backend.sidebar.domain.BlogSidebarItem;
import net.java21.blog.backend.sidebar.domain.SidebarItemType;
import net.java21.blog.backend.sidebar.dto.SidebarCommentResponse;
import net.java21.blog.backend.sidebar.dto.SidebarConfigRequest;
import net.java21.blog.backend.sidebar.dto.SidebarItemRequest;
import net.java21.blog.backend.sidebar.dto.SidebarViewResponse;
import net.java21.blog.backend.sidebar.repository.BlogSidebarItemRepository;
import net.java21.blog.backend.sidebar.repository.SidebarCommentRow;
import net.java21.blog.backend.sidebar.repository.SidebarQueryRepository;
import net.java21.blog.backend.stats.service.VisitorStatsService;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 사이드바(004 FR-060, FR-061, research B10). 설정은 블로그마다 10개 항목의 켜짐·순서이며 저장한 적이 없으면
 * {@link SidebarItemType#defaults()}. 보기는 켜진 항목만 데이터를 읽는다(꺼진 항목은 저장소를 부르지 않는다).
 */
@Service
public class SidebarService {

    static final int POST_LIMIT = 5;
    static final int COMMENT_LIMIT = 5;
    static final int TAG_LIMIT = 30;
    static final int EXCERPT_MAX = 50;

    private final BlogAccess blogAccess;
    private final BlogSidebarItemRepository itemRepository;
    private final SidebarQueryRepository queryRepository;
    private final TagQueryRepository tagQueryRepository;
    private final ArchiveService archiveService;
    private final VisitorStatsService visitorStats;

    public SidebarService(BlogAccess blogAccess, BlogSidebarItemRepository itemRepository,
            SidebarQueryRepository queryRepository, TagQueryRepository tagQueryRepository,
            ArchiveService archiveService, VisitorStatsService visitorStats) {
        this.blogAccess = blogAccess;
        this.itemRepository = itemRepository;
        this.queryRepository = queryRepository;
        this.tagQueryRepository = tagQueryRepository;
        this.archiveService = archiveService;
        this.visitorStats = visitorStats;
    }

    /** 공개 사이드바. 쿼리: 블로그 1 + 설정 1 + 켜진 데이터 항목마다 1. */
    @Transactional(readOnly = true)
    public SidebarViewResponse view(String handle) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        List<SidebarItemType> enabled = settings(blog.getId()).stream()
                .filter(SidebarItemType.Setting::enabled)
                .map(SidebarItemType.Setting::type)
                .toList();
        Set<SidebarItemType> on = enabled.isEmpty() ? EnumSet.noneOf(SidebarItemType.class) : EnumSet.copyOf(enabled);
        Long blogId = blog.getId();
        return new SidebarViewResponse(enabled,
                on.contains(SidebarItemType.RECENT_POSTS) ? queryRepository.findRecentPosts(blogId, POST_LIMIT) : null,
                on.contains(SidebarItemType.POPULAR_POSTS) ? queryRepository.findPopularPosts(blogId, POST_LIMIT)
                        : null,
                on.contains(SidebarItemType.RECENT_COMMENTS) ? recentComments(blogId) : null,
                on.contains(SidebarItemType.TAGS) ? tagQueryRepository.findBlogTags(blogId, TAG_LIMIT) : null,
                on.contains(SidebarItemType.ARCHIVE) ? archiveService.archive(blog) : null,
                on.contains(SidebarItemType.VISITORS) ? visitorStats.counts(blog) : null);
    }

    /** 주인 설정 화면: 10개 항목 순서대로(저장한 적이 없으면 기본 구성). */
    @Transactional(readOnly = true)
    public SidebarConfigRequest config(long userId, String handle) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        return toConfig(settings(blog.getId()));
    }

    /** 설정 전체 교체: 10개 항목을 빠짐·중복 없이 받아 배열 순서를 {@code sort_order}로 저장한다. */
    @Transactional
    public SidebarConfigRequest save(long userId, String handle, SidebarConfigRequest request) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        List<SidebarItemRequest> items = validate(request);
        itemRepository.deleteByBlogId(blog.getId());
        List<BlogSidebarItem> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            rows.add(new BlogSidebarItem(blog, items.get(i).type(), items.get(i).enabled(), i));
        }
        itemRepository.saveAll(rows);
        return new SidebarConfigRequest(List.copyOf(items));
    }

    private List<SidebarItemType.Setting> settings(Long blogId) {
        List<BlogSidebarItem> rows = itemRepository.findByBlogIdOrderBySortOrder(blogId);
        if (rows.isEmpty()) {
            return SidebarItemType.defaults();
        }
        return rows.stream().map(r -> new SidebarItemType.Setting(r.getType(), r.isEnabled())).toList();
    }

    private static SidebarConfigRequest toConfig(List<SidebarItemType.Setting> settings) {
        return new SidebarConfigRequest(settings.stream()
                .map(s -> new SidebarItemRequest(s.type(), s.enabled()))
                .toList());
    }

    private static List<SidebarItemRequest> validate(SidebarConfigRequest request) {
        List<SidebarItemRequest> items = request == null || request.items() == null ? List.of() : request.items();
        Set<SidebarItemType> seen = EnumSet.noneOf(SidebarItemType.class);
        boolean valid = items.size() == SidebarItemType.values().length;
        for (SidebarItemRequest item : items) {
            if (item == null || item.type() == null || !seen.add(item.type())) {
                valid = false;
            }
        }
        if (!valid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Sidebar must list every item exactly once",
                    List.of(new FieldError("items", "INVALID", Map.of("size", SidebarItemType.values().length))));
        }
        return items;
    }

    private List<SidebarCommentResponse> recentComments(Long blogId) {
        return queryRepository.findRecentComments(blogId, COMMENT_LIMIT).stream()
                .map(SidebarService::toComment)
                .toList();
    }

    private static SidebarCommentResponse toComment(SidebarCommentRow row) {
        boolean guest = row.guestName() != null;
        return new SidebarCommentResponse(row.id(), row.postId(), row.postTitle(), excerpt(row.content()),
                guest ? row.guestName() : row.nickname(), guest, row.createdAt());
    }

    /** 내용 앞 {@value #EXCERPT_MAX}자(코드 포인트), 줄바꿈은 공백으로. */
    static String excerpt(String content) {
        String flat = content == null ? "" : content.replaceAll("\\s+", " ").strip();
        if (flat.codePointCount(0, flat.length()) <= EXCERPT_MAX) {
            return flat;
        }
        return flat.substring(0, flat.offsetByCodePoints(0, EXCERPT_MAX)) + "…";
    }
}
