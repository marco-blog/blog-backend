package net.java21.blog.backend.manage.dto;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;

/**
 * 블로그 관리 글 목록 조건({@code GET /blogs/{handle}/manage/posts?status=&visibility=&category=&q=}, 006 FR-101).
 * 모든 값은 생략할 수 있다. {@code status}를 생략하면 휴지통을 뺀 전체, {@code DELETED}면 휴지통(보관 기간 안의 글)이다.
 *
 * @param status     DRAFT / PUBLISHED / SCHEDULED(004) / DELETED
 * @param visibility PUBLIC / PRIVATE / PROTECTED(004)
 * @param categoryId 카테고리 ID(상위면 하위 카테고리 글 포함)
 * @param q          제목 검색어(부분 일치, 대소문자 무시). 앞뒤 공백을 지우고 비었으면 조건 없음
 */
public record ManagePostFilter(PostStatus status, PostVisibility visibility, Long categoryId, String q) {

    public static final ManagePostFilter ALL = new ManagePostFilter(null, null, null, null);

    public ManagePostFilter {
        q = q == null || q.isBlank() ? null : q.strip();
    }

    public boolean trash() {
        return status == PostStatus.DELETED;
    }
}
