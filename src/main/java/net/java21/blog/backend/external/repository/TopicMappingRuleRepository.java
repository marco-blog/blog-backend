package net.java21.blog.backend.external.repository;

import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.external.domain.TopicMappingRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 주제 매핑 규칙(007 FR-121). */
public interface TopicMappingRuleRepository extends JpaRepository<TopicMappingRule, Long> {

    /** 분류에 쓰는 규칙 전체(주제 함께, 우선순위 큰 것·id 작은 것 먼저). 피드 한 번에 1회. */
    @Query("select r from TopicMappingRule r join fetch r.topic t left join fetch t.parent"
            + " order by r.priority desc, r.id asc")
    List<TopicMappingRule> findAllForMatching();

    Optional<TopicMappingRule> findByKeyword(String keyword);

    /** 관리 목록(우선순위 큰 것 먼저, 같으면 id). {@code q}가 있으면 키워드 부분 일치. 목록 1회 + 수 1회. */
    @Query(value = "select r from TopicMappingRule r join fetch r.createdBy"
            + " where (:q is null or r.keyword like concat('%', :q, '%')) order by r.priority desc, r.id asc",
            countQuery = "select count(r) from TopicMappingRule r"
                    + " where (:q is null or r.keyword like concat('%', :q, '%'))")
    Page<TopicMappingRule> search(@Param("q") String q, Pageable pageable);
}
