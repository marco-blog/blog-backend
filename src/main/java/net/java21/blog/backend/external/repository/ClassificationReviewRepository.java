package net.java21.blog.backend.external.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.external.domain.ClassificationReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 분류 검수(007 FR-121). 목록은 {@link ClassificationReviewQueryRepository}. */
public interface ClassificationReviewRepository extends JpaRepository<ClassificationReview, Long> {

    Optional<ClassificationReview> findByExternalPostId(Long externalPostId);

    @Query("select r from ClassificationReview r join fetch r.externalPost p where r.id in :ids")
    List<ClassificationReview> findAllWithPost(@Param("ids") Collection<Long> ids);
}
