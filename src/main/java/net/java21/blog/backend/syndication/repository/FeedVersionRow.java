package net.java21.blog.backend.syndication.repository;

import java.time.Instant;

/** 피드에 담을 글의 버전(ETag·Last-Modified 계산용, 본문 없이 가볍게 읽는다). */
public record FeedVersionRow(Long id, Instant updatedAt) {
}
