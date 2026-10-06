package net.java21.blog.backend.syndication.service;

import java.time.Instant;

import net.java21.blog.backend.blog.domain.FeedContentMode;

/** 피드를 만드는 블로그(와 카테고리)의 값. 카테고리 피드가 아니면 카테고리 값은 null. */
public record FeedSource(Long blogId, String handle, String title, String description, String ownerNickname,
        int itemCount, FeedContentMode contentMode, Instant blogUpdatedAt, Long categoryId, String categoryName,
        Instant categoryUpdatedAt) {
}
