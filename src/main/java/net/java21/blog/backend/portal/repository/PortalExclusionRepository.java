package net.java21.blog.backend.portal.repository;

import java.util.Optional;

import net.java21.blog.backend.portal.domain.PortalExclusion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalExclusionRepository extends JpaRepository<PortalExclusion, Long> {

    Optional<PortalExclusion> findByPostId(Long postId);

    boolean existsByPostId(Long postId);

    /** 007 외부 글 포털 제외. */
    Optional<PortalExclusion> findByExternalPostId(Long externalPostId);

    /** 007 외부 글 여러 개의 포털 제외(제외한 관리자 함께, 1회). */
    @org.springframework.data.jpa.repository.Query("select e from PortalExclusion e join fetch e.excludedBy"
            + " where e.externalPost.id in :ids")
    java.util.List<PortalExclusion> findByExternalPostIds(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    /** 외부 글을 지우기 전에 그 포털 제외 행을 지운다(FK에 CASCADE 없음, research E15·E16). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from PortalExclusion e where e.externalPost.id in :ids")
    int deleteByExternalPostIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
