package net.java21.blog.backend.category.dto;

import java.util.List;

/**
 * 카테고리 트리 노드 {@code { id, name, postCount, children }}(contracts/api.md). {@code postCount}는 "목록 노출 가능" 글 수이며
 * 상위 노드는 하위 카테고리의 글을 포함한다(tasks.md 결정 4). 하위 노드의 {@code children}은 항상 빈 배열이다(2단계).
 */
public record CategoryNode(Long id, String name, long postCount, List<CategoryNode> children) {
}
