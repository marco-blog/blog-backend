package net.java21.blog.backend.category.repository;

import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.category.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 이 블로그의 카테고리(다른 블로그 것이면 비어 있음). 쿼리 1회. */
    Optional<Category> findByIdAndBlogId(Long id, Long blogId);

    /** 이 블로그의 모든 카테고리(순서 변경용). 쿼리 1회. */
    List<Category> findByBlogId(Long blogId);
}
