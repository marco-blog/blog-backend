package net.java21.blog.backend.admin.portal.dto;

import java.time.Instant;

/** 추천 지정({@code POST /admin/portal/curations}). {@code sortOrder}를 생략하면 0. 검증은 서비스가 한다. */
public record CreateCurationRequest(Long postId, Instant startsAt, Instant endsAt, Integer sortOrder) {
}
