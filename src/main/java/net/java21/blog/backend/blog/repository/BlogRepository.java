package net.java21.blog.backend.blog.repository;

import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlogRepository extends JpaRepository<Blog, Long> {

    /** 삭제된 블로그를 포함해 주소가 쓰였는지(FR-159: 삭제된 블로그의 주소도 다시 쓸 수 없다). */
    boolean existsByHandle(String handle);

    /** 주소로 블로그와 주인을 함께 읽는다(쿼리 1회). 상태는 호출한 쪽({@code BlogAccess})이 판단한다. */
    @Query("select b from Blog b join fetch b.user where b.handle = :handle")
    Optional<Blog> findByHandleWithOwner(@Param("handle") String handle);

    long countByUserIdAndStatus(Long userId, BlogStatus status);
}
