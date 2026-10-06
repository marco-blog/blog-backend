package net.java21.blog.backend.topic.repository;

import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.topic.domain.Topic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicRepository extends JpaRepository<Topic, Long> {

    /** 주제와 대분류를 함께 읽는다(선택 가능 검사). */
    @Query("select t from Topic t left join fetch t.parent where t.id = :id")
    Optional<Topic> findWithParent(@Param("id") Long id);

    /** 한 부모의 자식(null이면 대분류) 전체를 순서대로(관리자 주제 추가·순서 바꾸기). */
    @Query("select t from Topic t where (:parentId is null and t.parent is null) or t.parent.id = :parentId"
            + " order by t.sortOrder, t.id")
    List<Topic> findChildren(@Param("parentId") Long parentId);

    boolean existsBySlug(String slug);
}
