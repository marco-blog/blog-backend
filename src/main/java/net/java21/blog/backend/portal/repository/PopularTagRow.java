package net.java21.blog.backend.portal.repository;

/** 인기 태그 한 줄(003 FR-087): 태그 이름과 최근 포털 노출 글 수. */
public record PopularTagRow(String name, long postCount) {
}
