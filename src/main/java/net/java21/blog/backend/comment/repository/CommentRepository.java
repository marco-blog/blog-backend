package net.java21.blog.backend.comment.repository;

import java.util.Optional;

import net.java21.blog.backend.comment.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 댓글과 그 글·블로그·블로그 주인을 함께 읽는다(쿼리 1회). 권한·노출 판단은 서비스가 한다. */
    @Query("select c from Comment c join fetch c.post p join fetch p.blog b join fetch b.user where c.id = :id")
    Optional<Comment> findWithPostAndOwner(@Param("id") Long id);

    /** 이 댓글에 달린 답글이 있는지. */
    boolean existsByParentId(Long parentId);

    /** 이 댓글에 달린 답글 중 {@code exceptId}가 아닌 것이 있는지(답글을 지우는 중에 부모 자리를 지울지 판단). */
    boolean existsByParentIdAndIdNot(Long parentId, Long exceptId);

    /**
     * 글의 표시되는 댓글 수({@code comment_count}, "구현 전 결정 사항" 5번)를 같은 트랜잭션에서 원자적으로 바꾼다(UPDATE 1회).
     * 0 아래로 내려가지 않는다. {@code updated_at = updated_at}: MySQL의 {@code ON UPDATE CURRENT_TIMESTAMP}가
     * 댓글만으로 글의 수정 시각을 바꾸지 않게 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Post p set p.commentCount = case when p.commentCount + :delta < 0 then 0"
            + " else p.commentCount + :delta end, p.updatedAt = p.updatedAt where p.id = :postId")
    int changeCommentCount(@Param("postId") Long postId, @Param("delta") int delta);
}
