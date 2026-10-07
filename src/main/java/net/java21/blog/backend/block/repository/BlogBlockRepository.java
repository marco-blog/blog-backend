package net.java21.blog.backend.block.repository;

import net.java21.blog.backend.block.domain.BlogBlock;
import net.java21.blog.backend.block.domain.BlogBlockId;
import org.springframework.data.jpa.repository.JpaRepository;

/** 블로그 차단 행(004 FR-146). 차단 확인은 PK 조회 1회({@code existsById}). */
public interface BlogBlockRepository extends JpaRepository<BlogBlock, BlogBlockId> {
}
