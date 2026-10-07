package net.java21.blog.backend.admin.dashboard;

/**
 * 콘솔 대시보드의 "처리 대기 신고" 수(006 FR-103, research A3). 신고는 005가 만들므로 006은 자리만 두고({@link NoPendingReportCounter}),
 * 005가 이 인터페이스의 구현을 빈으로 등록해 바꾼다(003 {@code BlogPenaltyPolicy}와 같은 방식). 캐시하지 않고 매번 부른다.
 */
public interface PendingReportCounter {

    /** 처리 대기 신고 수. 신고 기능이 없으면 null(화면에서 카드를 숨긴다). */
    Long countPending();
}
