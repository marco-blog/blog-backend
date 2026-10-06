package net.java21.blog.backend.manage.dto;

import java.util.List;

import net.java21.blog.backend.post.dto.PostSummaryResponse;

/**
 * 블로그 관리 대시보드(006 FR-100, 001 범위). 방문자 수(004 FR-067)·방명록(004)은 해당 스펙이 필드를 더한다.
 *
 * @param draftCount     임시저장(발행 전) 글 수
 * @param recentPosts    최근 글 5편(휴지통 제외)
 * @param newComments7d  최근 7일 새 댓글 수(표시되는 댓글, 휴지통 글 제외)
 * @param recentComments 최근 댓글 5건
 */
public record DashboardResponse(long draftCount, List<PostSummaryResponse> recentPosts, long newComments7d,
        List<ManageCommentResponse> recentComments) {
}
