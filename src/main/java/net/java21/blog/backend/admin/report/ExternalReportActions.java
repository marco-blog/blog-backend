package net.java21.blog.backend.admin.report;

/**
 * 007 외부 대상 신고의 조치(research E17). {@code REMOVE_FROM_PORTAL}은 외부 글 내림({@code REPORT}), {@code BLOCK_EXTERNAL_BLOG}는
 * 외부 블로그 차단(사유 = 처리 메모). 둘 다 이미 그 상태면 그대로 둔다(같은 대상의 신고를 닫을 수 있게).
 */
public interface ExternalReportActions {

    void removeFromPortal(long adminId, long externalPostId, String reason, String requestIp);

    void blockExternalBlog(long adminId, long externalBlogId, String reason, String requestIp);
}
