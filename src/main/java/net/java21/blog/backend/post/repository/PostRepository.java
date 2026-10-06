package net.java21.blog.backend.post.repository;

import java.util.Optional;

import net.java21.blog.backend.post.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    /** 글과 블로그·주인을 함께 읽는다(쿼리 1회). 노출·소유 판단은 호출한 쪽({@link PostExposure}, {@code PostAccess})이 한다. */
    @Query("select p from Post p join fetch p.blog b join fetch b.user where p.id = :id")
    Optional<Post> findWithBlogAndOwner(@Param("id") Long id);

    /**
     * 조회수 원자적 증가(UPDATE 1회, FR-020). 동시 요청에도 잃는 값이 없다.
     * {@code updated_at = updated_at}: MySQL의 {@code ON UPDATE CURRENT_TIMESTAMP}가 조회만으로 수정 시각을 바꾸지 않게 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Post p set p.viewCount = p.viewCount + 1, p.updatedAt = p.updatedAt where p.id = :id")
    int incrementViewCount(@Param("id") Long id);
}
