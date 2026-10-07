package net.java21.blog.backend.admin.topic.dto;

import java.util.List;

/**
 * 주제 순서 바꾸기({@code PUT /admin/topics/order}): 그 부모(null이면 대분류)의 자식 전체를 새 순서로.
 */
public record TopicOrderRequest(Long parentId, List<Long> ids) {
}
