package net.java21.blog.backend.topic.repository;

import java.util.Optional;

import net.java21.blog.backend.topic.domain.Topic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicRepository extends JpaRepository<Topic, Long> {

    /** 주제와 대분류를 함께 읽는다(선택 가능 검사). */
    @Query("select t from Topic t left join fetch t.parent where t.id = :id")
    Optional<Topic> findWithParent(@Param("id") Long id);
}
