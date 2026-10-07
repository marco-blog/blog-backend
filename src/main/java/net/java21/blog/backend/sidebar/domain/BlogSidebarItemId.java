package net.java21.blog.backend.sidebar.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@link BlogSidebarItem}의 복합 키(blog_id, item_type). */
@Embeddable
public record BlogSidebarItemId(
        @Column(name = "blog_id") Long blogId,
        @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "item_type", length = 20)
        SidebarItemType itemType) implements Serializable {
}
