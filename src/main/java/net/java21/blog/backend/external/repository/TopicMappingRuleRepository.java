package net.java21.blog.backend.external.repository;

import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.external.domain.TopicMappingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 주제 매핑 규칙(007 FR-121). */
public interface TopicMappingRuleRepository extends JpaRepository<TopicMappingRule, Long> {

    /** 분류에 쓰는 규칙 전체(주제 함께, 우선순위 큰 것·id 작은 것 먼저). 피드 한 번에 1회. */
    @Query("select r from TopicMappingRule r join fetch r.topic t left join fetch t.parent"
            + " order by r.priority desc, r.id asc")
    List<TopicMappingRule> findAllForMatching();

    Optional<TopicMappingRule> findByKeyword(String keyword);
}
