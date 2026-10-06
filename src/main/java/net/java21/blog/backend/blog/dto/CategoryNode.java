package net.java21.blog.backend.blog.dto;

import java.util.List;

/** 카테고리 트리 노드 {@code { id, name, postCount, children }}(contracts/api.md). 카테고리는 US2에서 채운다. */
public record CategoryNode(Long id, String name, long postCount, List<CategoryNode> children) {
}
