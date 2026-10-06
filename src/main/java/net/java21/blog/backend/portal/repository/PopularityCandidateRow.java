package net.java21.blog.backend.portal.repository;

import java.time.Instant;

/** 인기 점수 후보 글(포털 노출 글만): 블로그(2편 제한·감점), 주제(주제 페이지 인기순), 발행 시각(감쇠). */
public record PopularityCandidateRow(Long postId, Long blogId, Long topicId, Instant publishedAt) {
}
