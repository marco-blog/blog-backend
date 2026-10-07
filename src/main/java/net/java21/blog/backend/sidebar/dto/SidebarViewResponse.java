package net.java21.blog.backend.sidebar.dto;

import java.util.List;

import net.java21.blog.backend.post.dto.ArchiveMonthResponse;
import net.java21.blog.backend.sidebar.domain.SidebarItemType;
import net.java21.blog.backend.stats.dto.VisitorCountsResponse;
import net.java21.blog.backend.tag.dto.BlogTagResponse;

/**
 * 공개 사이드바(004 contracts/api.md {@code SidebarView}). {@code items}는 켜진 항목만 순서대로, 데이터 필드는 그 항목이 켜졌을 때만
 * 값이고 꺼졌으면 null. PROFILE·CATEGORIES·SEARCH·FEED_LINKS는 front가 블로그 응답으로 그린다.
 */
public record SidebarViewResponse(List<SidebarItemType> items, List<SidebarPostResponse> recentPosts,
        List<SidebarPostResponse> popularPosts, List<SidebarCommentResponse> recentComments, List<BlogTagResponse> tags,
        List<ArchiveMonthResponse> archive, VisitorCountsResponse visitors) {
}
