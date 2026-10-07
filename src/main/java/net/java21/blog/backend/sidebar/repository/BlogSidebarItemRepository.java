package net.java21.blog.backend.sidebar.repository;

import java.util.List;

import net.java21.blog.backend.sidebar.domain.BlogSidebarItem;
import net.java21.blog.backend.sidebar.domain.BlogSidebarItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 사이드바 설정 읽기·전체 교체(004 research B10). 각 메서드는 쿼리 1회. */
public interface BlogSidebarItemRepository extends JpaRepository<BlogSidebarItem, BlogSidebarItemId> {

    /** 블로그의 사이드바 항목(순서대로). 저장한 적이 없으면 빈 목록. */
    @Query("select i from BlogSidebarItem i where i.id.blogId = :blogId order by i.sortOrder asc")
    List<BlogSidebarItem> findByBlogIdOrderBySortOrder(@Param("blogId") Long blogId);

    /** 블로그의 사이드바 항목을 모두 지운다(DELETE 1회). @return 지운 행 수 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from BlogSidebarItem i where i.id.blogId = :blogId")
    int deleteByBlogId(@Param("blogId") Long blogId);
}
