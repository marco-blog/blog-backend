package net.java21.blog.backend.admin.topic.dto;

import java.util.Map;

/**
 * 주제 추가({@code POST /admin/topics}). 형식 검사는 서비스가 해 {@code names.ja} 같은 필드 이름으로 알린다.
 *
 * @param parentId  대분류 id(null이면 대분류를 추가)
 * @param names     4개 언어 이름(키 {@code ko}, {@code en}, {@code ja}, {@code zh-CN})
 * @param cardColor {@code #RRGGBB} 또는 null
 */
public record CreateTopicRequest(Long parentId, String slug, Map<String, String> names, String cardColor) {
}
