package net.java21.blog.backend.category.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.domain.BaseTimeEntity;

/**
 * 블로그별 2단계 카테고리(categories, T176, FR-023·024). {@code parent}가 null이면 상위 카테고리이고, 부모의 부모는 없어야 한다
 * (깊이 규칙은 {@code CategoryService}가 검사한다). 같은 부모 아래 이름은 유일하다: DB의 UNIQUE(blog_id, parent_id, name)는
 * 상위 카테고리({@code parent_id} NULL)끼리를 막지 못하므로 서비스가 먼저 확인한다(tasks.md 결정 4).
 */
@Entity
@Table(name = "categories")
public class Category extends BaseTimeEntity {

    public static final int NAME_MAX = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blog_id", nullable = false)
    private Blog blog;

    /** NULL=상위 카테고리 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Category() {
    }

    public Category(Blog blog, Category parent, String name, int sortOrder) {
        this.blog = blog;
        this.parent = parent;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public void rename(String name) {
        this.name = name;
    }

    /** 순서 변경(PUT .../order). 깊이 규칙은 호출한 쪽이 먼저 확인한다. */
    public void place(Category parent, int sortOrder) {
        this.parent = parent;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Blog getBlog() {
        return blog;
    }

    public Category getParent() {
        return parent;
    }

    public String getName() {
        return name;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
