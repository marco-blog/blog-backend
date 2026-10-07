package net.java21.blog.backend.stats.dto;

/** 오늘·어제·전체 방문자 수(004 FR-067). 날짜는 {@code blog.stats.time-zone} 기준. */
public record VisitorCountsResponse(long today, long yesterday, long total) {
}
