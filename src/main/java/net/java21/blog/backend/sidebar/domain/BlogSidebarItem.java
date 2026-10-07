package net.java21.blog.backend.sidebar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.domain.BaseTimeEntity;

/**
 * 블로그 사이드바 항목(blog_sidebar_items, 004 FR-060). 블로그마다 10개 항목을 모두 한 번에 바꾼다(전체 교체, research B10).
 * 행이 하나도 없으면 {@link SidebarItemType#defaults()} 구성으로 보여준다.
 */
@Entity
@Table(name = "blog_sidebar_items")
public class BlogSidebarItem extends BaseTimeEntity {

    @EmbeddedId
    private BlogSidebarItemId id;

    @MapsId("blogId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blog_id")
    private Blog blog;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected BlogSidebarItem() {
    }

    public BlogSidebarItem(Blog blog, SidebarItemType type, boolean enabled, int sortOrder) {
        this.blog = blog;
        this.id = new BlogSidebarItemId(blog.getId(), type);
        this.enabled = enabled;
        this.sortOrder = sortOrder;
    }

    public BlogSidebarItemId getId() {
        return id;
    }

    public SidebarItemType getType() {
        return id.itemType();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
