package net.java21.blog.backend.export.repository;

import net.java21.blog.backend.export.domain.BlogExport;
import org.springframework.data.jpa.repository.JpaRepository;

/** 블로그 백업 행(004 FR-145). 조회·조건부 UPDATE는 US4가 더한다. */
public interface BlogExportRepository extends JpaRepository<BlogExport, Long> {
}
