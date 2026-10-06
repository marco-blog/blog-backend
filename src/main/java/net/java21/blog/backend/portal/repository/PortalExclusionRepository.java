package net.java21.blog.backend.portal.repository;

import java.util.Optional;

import net.java21.blog.backend.portal.domain.PortalExclusion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalExclusionRepository extends JpaRepository<PortalExclusion, Long> {

    Optional<PortalExclusion> findByPostId(Long postId);

    boolean existsByPostId(Long postId);
}
