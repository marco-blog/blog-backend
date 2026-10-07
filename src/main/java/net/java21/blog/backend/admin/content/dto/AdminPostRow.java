package net.java21.blog.backend.admin.content.dto;

import java.time.Instant;

import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;

/**
 * 콘텐츠 관리 글 목록 한 행(006 contracts/api.md {@code AdminPostRow}). 본문·요약·대표 이미지·비밀번호 해시는 없다(FR-104).
 */
public record AdminPostRow(long id, String title, BlogRef blog, ContentAuthor author, PostStatus status,
        PostVisibility visibility, Instant publishedAt, Instant createdAt, Instant deletedAt, int commentCount) {

    public record BlogRef(String handle, String title, BlogStatus status) {
    }
}
