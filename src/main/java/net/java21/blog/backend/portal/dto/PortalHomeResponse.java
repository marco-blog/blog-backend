package net.java21.blog.backend.portal.dto;

import java.time.Instant;
import java.util.List;

/**
 * 포털 메인 묶음(003 contracts/api.md {@code PortalHome}). 빈 영역은 {@code []}이며 front가 숨긴다. {@code generatedAt}은 이 묶음을
 * 계산한 시각(캐시, 최대 5분 전)이다.
 */
public record PortalHomeResponse(List<PortalCardResponse> curations, List<PortalCardResponse> popular,
        LatestSection latest, List<PopularTagResponse> popularTags, List<NewBlogResponse> newBlogs,
        Instant generatedAt) {

    public PortalHomeResponse {
        curations = List.copyOf(curations);
        popular = List.copyOf(popular);
        popularTags = List.copyOf(popularTags);
        newBlogs = List.copyOf(newBlogs);
    }
}
