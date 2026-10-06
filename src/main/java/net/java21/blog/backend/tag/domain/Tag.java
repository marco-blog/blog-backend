package net.java21.blog.backend.tag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;

/** 서비스 공통 태그(tags, T177, FR-025). 이름은 {@link TagNormalizer}로 정규화한 값이며 유일하다. */
@Entity
@Table(name = "tags")
public class Tag extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = TagNormalizer.NAME_MAX)
    private String name;

    protected Tag() {
    }

    public Tag(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
