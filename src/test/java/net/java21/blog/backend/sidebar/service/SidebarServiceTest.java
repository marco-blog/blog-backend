package net.java21.blog.backend.sidebar.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.dto.ArchiveMonthResponse;
import net.java21.blog.backend.post.service.ArchiveService;
import net.java21.blog.backend.sidebar.domain.BlogSidebarItem;
import net.java21.blog.backend.sidebar.domain.SidebarItemType;
import net.java21.blog.backend.sidebar.dto.SidebarConfigRequest;
import net.java21.blog.backend.sidebar.dto.SidebarItemRequest;
import net.java21.blog.backend.sidebar.dto.SidebarPostResponse;
import net.java21.blog.backend.sidebar.dto.SidebarViewResponse;
import net.java21.blog.backend.sidebar.repository.BlogSidebarItemRepository;
import net.java21.blog.backend.sidebar.repository.SidebarCommentRow;
import net.java21.blog.backend.sidebar.repository.SidebarQueryRepository;
import net.java21.blog.backend.stats.dto.VisitorCountsResponse;
import net.java21.blog.backend.stats.service.VisitorStatsService;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.dto.BlogTagResponse;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 사이드바 설정·보기(T053, FR-060, FR-061, research B10). */
@ExtendWith(MockitoExtension.class)
class SidebarServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    @Mock
    private BlogAccess blogAccess;
    @Mock
    private BlogSidebarItemRepository itemRepository;
    @Mock
    private SidebarQueryRepository queryRepository;
    @Mock
    private TagQueryRepository tagQueryRepository;
    @Mock
    private ArchiveService archiveService;
    @Mock
    private VisitorStatsService visitorStats;

    private SidebarService service;
    private Blog blog;

    @BeforeEach
    void setUp() {
        service = new SidebarService(blogAccess, itemRepository, queryRepository, tagQueryRepository, archiveService,
                visitorStats);
        blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        lenient().when(blogAccess.requireVisibleBlog("marco")).thenReturn(blog);
        lenient().when(blogAccess.requireOwnedActiveBlog("marco", 1L)).thenReturn(blog);
    }

    @Test
    void defaultsWhenNothingSaved() {
        when(itemRepository.findByBlogIdOrderBySortOrder(10L)).thenReturn(List.of());

        SidebarConfigRequest config = service.config(1L, "marco");

        assertThat(config.items()).extracting(SidebarItemRequest::type).containsExactly(SidebarItemType.PROFILE,
                SidebarItemType.CATEGORIES, SidebarItemType.RECENT_POSTS, SidebarItemType.TAGS, SidebarItemType.ARCHIVE,
                SidebarItemType.SEARCH, SidebarItemType.FEED_LINKS, SidebarItemType.RECENT_COMMENTS,
                SidebarItemType.POPULAR_POSTS, SidebarItemType.VISITORS);
        assertThat(config.items()).extracting(SidebarItemRequest::enabled)
                .containsExactly(true, true, true, true, true, true, true, false, false, false);
    }

    @Test
    void defaultViewLoadsOnlyEnabledDataItems() {
        when(itemRepository.findByBlogIdOrderBySortOrder(10L)).thenReturn(List.of());
        List<SidebarPostResponse> recent = List.of(new SidebarPostResponse(1L, "첫 글", NOW));
        when(queryRepository.findRecentPosts(10L, 5)).thenReturn(recent);
        when(tagQueryRepository.findBlogTags(10L, 30)).thenReturn(List.of(new BlogTagResponse("spring", 14)));
        when(archiveService.archive(blog)).thenReturn(List.of(new ArchiveMonthResponse(2026, 10, 3)));

        SidebarViewResponse view = service.view("marco");

        assertThat(view.items()).containsExactly(SidebarItemType.PROFILE, SidebarItemType.CATEGORIES,
                SidebarItemType.RECENT_POSTS, SidebarItemType.TAGS, SidebarItemType.ARCHIVE, SidebarItemType.SEARCH,
                SidebarItemType.FEED_LINKS);
        assertThat(view.recentPosts()).isEqualTo(recent);
        assertThat(view.tags()).hasSize(1);
        assertThat(view.archive()).hasSize(1);
        assertThat(view.popularPosts()).isNull();
        assertThat(view.recentComments()).isNull();
        assertThat(view.visitors()).isNull();
        verify(queryRepository, never()).findPopularPosts(anyLong(), anyInt());
        verify(queryRepository, never()).findRecentComments(anyLong(), anyInt());
        verifyNoInteractions(visitorStats);
    }

    @Test
    void savedOrderAndOptionalItems() {
        when(itemRepository.findByBlogIdOrderBySortOrder(10L)).thenReturn(List.of(
                new BlogSidebarItem(blog, SidebarItemType.VISITORS, true, 0),
                new BlogSidebarItem(blog, SidebarItemType.RECENT_COMMENTS, true, 1),
                new BlogSidebarItem(blog, SidebarItemType.POPULAR_POSTS, true, 2),
                new BlogSidebarItem(blog, SidebarItemType.TAGS, false, 3),
                new BlogSidebarItem(blog, SidebarItemType.RECENT_POSTS, false, 4)));
        when(visitorStats.counts(blog)).thenReturn(new VisitorCountsResponse(1, 2, 3));
        when(queryRepository.findPopularPosts(10L, 5)).thenReturn(List.of());
        when(queryRepository.findRecentComments(10L, 5)).thenReturn(List.of(
                new SidebarCommentRow(5L, 9L, "글", "가".repeat(60), null, "손님", NOW),
                new SidebarCommentRow(6L, 9L, "글", "짧은\n댓글", "리더", null, NOW)));

        SidebarViewResponse view = service.view("marco");

        assertThat(view.items()).containsExactly(SidebarItemType.VISITORS, SidebarItemType.RECENT_COMMENTS,
                SidebarItemType.POPULAR_POSTS);
        assertThat(view.visitors()).isEqualTo(new VisitorCountsResponse(1, 2, 3));
        assertThat(view.recentComments().get(0).excerpt()).isEqualTo("가".repeat(50) + "…");
        assertThat(view.recentComments().get(0).guest()).isTrue();
        assertThat(view.recentComments().get(0).authorName()).isEqualTo("손님");
        assertThat(view.recentComments().get(1).excerpt()).isEqualTo("짧은 댓글");
        assertThat(view.recentComments().get(1).authorName()).isEqualTo("리더");
        assertThat(view.recentPosts()).isNull();
        assertThat(view.tags()).isNull();
        verifyNoInteractions(tagQueryRepository, archiveService);
    }

    @Test
    void allOffShowsNothing() {
        List<BlogSidebarItem> rows = new ArrayList<>();
        for (SidebarItemType type : SidebarItemType.values()) {
            rows.add(new BlogSidebarItem(blog, type, false, rows.size()));
        }
        when(itemRepository.findByBlogIdOrderBySortOrder(10L)).thenReturn(rows);
        assertThat(service.view("marco").items()).isEmpty();
    }

    @Test
    void saveReplacesAllRowsInArrayOrder() {
        List<SidebarItemRequest> items = new ArrayList<>(Arrays.stream(SidebarItemType.values())
                .map(t -> new SidebarItemRequest(t, t != SidebarItemType.CATEGORIES))
                .toList());
        java.util.Collections.reverse(items);

        SidebarConfigRequest saved = service.save(1L, "marco", new SidebarConfigRequest(items));

        InOrder order = inOrder(itemRepository);
        order.verify(itemRepository).deleteByBlogId(10L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BlogSidebarItem>> rows = ArgumentCaptor.forClass(List.class);
        order.verify(itemRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(10);
        assertThat(rows.getValue().get(0).getType()).isEqualTo(SidebarItemType.FEED_LINKS);
        assertThat(rows.getValue().get(0).getSortOrder()).isZero();
        assertThat(rows.getValue().get(9).getType()).isEqualTo(SidebarItemType.PROFILE);
        assertThat(rows.getValue().get(9).getSortOrder()).isEqualTo(9);
        assertThat(rows.getValue()).filteredOn(r -> !r.isEnabled()).extracting(BlogSidebarItem::getType)
                .containsExactly(SidebarItemType.CATEGORIES);
        assertThat(saved.items()).isEqualTo(items);
    }

    @Test
    void saveRejectsMissingDuplicateOrUnknownItems() {
        List<SidebarItemRequest> nine = Arrays.stream(SidebarItemType.values()).skip(1)
                .map(t -> new SidebarItemRequest(t, true)).toList();
        assertCode(() -> service.save(1L, "marco", new SidebarConfigRequest(nine)), ErrorCode.VALIDATION_FAILED);
        List<SidebarItemRequest> duplicate = new ArrayList<>(nine);
        duplicate.add(new SidebarItemRequest(SidebarItemType.TAGS, true));
        assertCode(() -> service.save(1L, "marco", new SidebarConfigRequest(duplicate)), ErrorCode.VALIDATION_FAILED);
        List<SidebarItemRequest> withNull = new ArrayList<>(nine);
        withNull.add(new SidebarItemRequest(null, true));
        assertCode(() -> service.save(1L, "marco", new SidebarConfigRequest(withNull)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.save(1L, "marco", new SidebarConfigRequest(null)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.save(1L, "marco", null), ErrorCode.VALIDATION_FAILED);
        verify(itemRepository, never()).deleteByBlogId(anyLong());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    void othersCannotConfigure() {
        when(blogAccess.requireOwnedActiveBlog("marco", 2L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        assertCode(() -> service.config(2L, "marco"), ErrorCode.FORBIDDEN);
        assertCode(() -> service.save(2L, "marco", new SidebarConfigRequest(List.of())), ErrorCode.FORBIDDEN);
    }

    @Test
    void excerptKeepsShortTextAndHandlesNull() {
        assertThat(SidebarService.excerpt(null)).isEmpty();
        assertThat(SidebarService.excerpt(" 가 ")).isEqualTo("가");
        assertThat(SidebarService.excerpt("😀".repeat(51))).isEqualTo("😀".repeat(50) + "…");
    }
}
