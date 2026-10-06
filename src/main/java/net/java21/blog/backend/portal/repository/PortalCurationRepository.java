package net.java21.blog.backend.portal.repository;

import net.java21.blog.backend.portal.domain.PortalCuration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalCurationRepository extends JpaRepository<PortalCuration, Long> {
}
