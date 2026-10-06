package net.java21.blog.backend.post.dto;

import net.java21.blog.backend.category.domain.Category;

/** 글의 카테고리 {@code { id, name }}. 미분류는 null. */
public record CategoryRef(Long id, String name) {

    /** 카테고리 id가 없으면(미분류) null. */
    public static CategoryRef of(Long id, String name) {
        return id == null ? null : new CategoryRef(id, name);
    }

    /** 읽어 둔(또는 지연 로딩되는) 카테고리. 미분류면 null. */
    public static CategoryRef of(Category category) {
        return category == null ? null : new CategoryRef(category.getId(), category.getName());
    }
}
