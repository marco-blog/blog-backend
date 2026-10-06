package net.java21.blog.backend.portal.dto;

import java.util.List;

/** 포털 메인의 최신 글 묶음(003 contracts/api.md {@code PortalHome.latest}). {@code nextCursor}가 null이면 더 없다. */
public record LatestSection(List<PortalCardResponse> items, String nextCursor) {

    public LatestSection {
        items = List.copyOf(items);
    }
}
