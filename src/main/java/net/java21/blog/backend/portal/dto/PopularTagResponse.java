package net.java21.blog.backend.portal.dto;

import net.java21.blog.backend.portal.repository.PopularTagRow;

/** 인기 태그(003 contracts/api.md {@code PortalHome.popularTags}). */
public record PopularTagResponse(String name, long postCount) {

    public static PopularTagResponse from(PopularTagRow row) {
        return new PopularTagResponse(row.name(), row.postCount());
    }
}
