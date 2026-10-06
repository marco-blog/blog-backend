package net.java21.blog.backend.manage.dto;

/** 일괄 작업 결과 {@code { updated: n }}: 실제로 바뀐 글 수(이미 휴지통인 글은 세지 않는다). */
public record BulkPostResponse(long updated) {
}
