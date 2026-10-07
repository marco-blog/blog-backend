package net.java21.blog.backend.post.dto;

/** 월별 보관함 한 줄(004 FR-061): 연·월은 {@code blog.stats.time-zone} 기준, 그 달의 목록 노출 가능 글 수. */
public record ArchiveMonthResponse(int year, int month, long postCount) {
}
