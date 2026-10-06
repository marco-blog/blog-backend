package net.java21.blog.backend.admin.portal.dto;

/** 포털 제외·사유 변경({@code PUT /admin/portal/exclusions/{postId}}). 사유 1~500자는 서비스가 검사한다. */
public record ExclusionRequest(String reason) {
}
