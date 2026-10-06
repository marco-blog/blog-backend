package net.java21.blog.backend.tag.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.tag.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

    /** 이름이 같은 태그들(쿼리 1회). 이름 비교는 DB 정렬 규칙을 따른다. */
    List<Tag> findByNameIn(Collection<String> names);

    Optional<Tag> findByName(String name);
}
