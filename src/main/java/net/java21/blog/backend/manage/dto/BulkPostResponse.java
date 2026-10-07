package net.java21.blog.backend.manage.dto;

/**
 * 일괄 작업 결과 {@code { updated: n, skipped: m }}: 실제로 바뀐 글 수(이미 휴지통인 글은 세지 않는다)와, 005 관리자가 숨긴 글이라
 * 건너뛴 수(공개 범위 변경·공지만, 005 FR-041).
 */
public record BulkPostResponse(long updated, long skipped) {

    public BulkPostResponse(long updated) {
        this(updated, 0);
    }
}
