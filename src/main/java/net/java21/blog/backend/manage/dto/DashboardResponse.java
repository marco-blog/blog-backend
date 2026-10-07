package net.java21.blog.backend.manage.dto;

import java.util.List;

import net.java21.blog.backend.guestbook.dto.GuestbookEntryResponse;
import net.java21.blog.backend.stats.dto.VisitorCountsResponse;

import net.java21.blog.backend.post.dto.PostSummaryResponse;

/**
 * 블로그 관리 대시보드(006 FR-100). 방명록·방문자는 004가 더했다.
 *
 * @param draftCount     임시저장(발행 전) 글 수
 * @param recentPosts    최근 글 5편(휴지통 제외)
 * @param newComments7d  최근 7일 새 댓글 수(표시되는 댓글, 휴지통 글 제외)
 * @param recentComments 최근 댓글 5건
 * @param newGuestbook7d 최근 7일 새 방명록 글 수(답글 제외, 004)
 * @param recentGuestbook 최근 방명록 글 5건(답글 제외, 주인이므로 비밀글 내용 포함, 004)
 * @param visitors       오늘·어제·전체 방문자(004 FR-067)
 */
public record DashboardResponse(long draftCount, List<PostSummaryResponse> recentPosts, long newComments7d,
        List<ManageCommentResponse> recentComments, long newGuestbook7d,
        List<GuestbookEntryResponse> recentGuestbook, VisitorCountsResponse visitors) {
}
