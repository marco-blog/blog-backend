package net.java21.blog.backend.admin.portal.dto;

import java.time.Instant;

/** 추천 수정({@code PATCH /admin/portal/curations/{id}}). 보낸 값만 바꾼다(지울 수 있는 값이 없어 null은 "보내지 않음"). */
public record UpdateCurationRequest(Instant startsAt, Instant endsAt, Integer sortOrder) {
}
