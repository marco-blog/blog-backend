package net.java21.blog.backend.topic.dto;

import java.util.List;
import java.util.Map;

/**
 * 공개 주제 트리 한 칸(003 contracts/api.md {@code TopicNode}). 이름은 4개 언어 모두 주고 front가 화면 언어로 고른다(결정 표 8번).
 *
 * @param cardColor {@code #RRGGBB} 또는 null(소분류가 null이면 front가 대분류 색을 쓴다)
 * @param onTab     주제 탭에 보이는지(FR-147 자동 숨김 계산, 최대 5분 지연)
 * @param children  대분류의 소분류(순서대로). 소분류는 항상 빈 목록
 */
public record TopicNode(Long id, String slug, Long parentId, Map<String, String> names, String cardColor,
        boolean onTab, List<TopicNode> children) {
}
