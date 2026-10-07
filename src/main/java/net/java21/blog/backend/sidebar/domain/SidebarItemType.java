package net.java21.blog.backend.sidebar.domain;

import java.util.List;

/**
 * 사이드바 항목(004 FR-060, data-model blog_sidebar_items). 값 이름은 {@code item_type}에 그대로 저장한다(20자 이내).
 * 저장한 적이 없는 블로그는 {@link #defaults()} 구성(이 enum의 선언 순서와 다름)으로 보여준다.
 */
public enum SidebarItemType {
    PROFILE,
    CATEGORIES,
    RECENT_POSTS,
    RECENT_COMMENTS,
    POPULAR_POSTS,
    TAGS,
    ARCHIVE,
    VISITORS,
    SEARCH,
    FEED_LINKS;

    /** 항목 하나의 켜짐 여부. */
    public record Setting(SidebarItemType type, boolean enabled) {
    }

    /**
     * 기본 구성(contracts/api.md 사이드바): PROFILE, CATEGORIES, RECENT_POSTS, TAGS, ARCHIVE, SEARCH, FEED_LINKS 켜짐 →
     * RECENT_COMMENTS, POPULAR_POSTS, VISITORS 꺼짐(이 순서).
     */
    public static List<Setting> defaults() {
        return List.of(
                new Setting(PROFILE, true),
                new Setting(CATEGORIES, true),
                new Setting(RECENT_POSTS, true),
                new Setting(TAGS, true),
                new Setting(ARCHIVE, true),
                new Setting(SEARCH, true),
                new Setting(FEED_LINKS, true),
                new Setting(RECENT_COMMENTS, false),
                new Setting(POPULAR_POSTS, false),
                new Setting(VISITORS, false));
    }
}
