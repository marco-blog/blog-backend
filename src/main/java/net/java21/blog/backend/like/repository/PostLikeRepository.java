package net.java21.blog.backend.like.repository;

import java.time.Instant;
import java.util.Optional;

import net.java21.blog.backend.like.domain.PostLike;
import net.java21.blog.backend.like.domain.PostLikeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 좋아요 쓰기(002 research D1). 엔티티를 읽어 값을 바꾸지 않고 {@code INSERT IGNORE}·DELETE의 영향 행 수와 원자적 UPDATE만 쓴다.
 * 같은 회원·글의 동시 요청도 행은 하나, 수는 실제로 생기거나 지워진 행만큼만 바뀐다. 각 메서드는 쿼리 1회.
 */
public interface PostLikeRepository extends JpaRepository<PostLike, PostLikeId> {

    /** 좋아요 행을 넣는다. 이미 있으면 아무것도 하지 않는다. @return 새로 넣었으면 1, 이미 있었으면 0 */
    @Modifying
    @Query(value = "INSERT IGNORE INTO post_likes (user_id, post_id, created_at) VALUES (:userId, :postId, :now)",
            nativeQuery = true)
    int insertIgnore(@Param("userId") Long userId, @Param("postId") Long postId, @Param("now") Instant now);

    /** 좋아요 행을 지운다. @return 지웠으면 1, 없었으면 0 */
    @Modifying
    @Query("delete from PostLike l where l.id.userId = :userId and l.id.postId = :postId")
    int delete(@Param("userId") Long userId, @Param("postId") Long postId);

    /**
     * 글의 좋아요 수를 원자적으로 바꾼다(UPDATE 1회). 0 아래로 내려가지 않는다. {@code updated_at = updated_at}: 좋아요 하나로
     * 글의 수정 시각(피드 ETag·사이트맵 lastmod)이 바뀌지 않게 한다("구현 전 결정 사항" 15번).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Post p set p.likeCount = case when p.likeCount + :delta < 0 then 0"
            + " else p.likeCount + :delta end, p.updatedAt = p.updatedAt where p.id = :postId")
    int changeLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    /**
     * 글의 좋아요 수를 읽으며 글 행을 잠근다({@code SELECT ... FOR UPDATE}). 같은 글의 좋아요·취소를 줄 세워, 서로 다른 회원이 동시에
     * 눌러도 외래 키 공유 잠금 때문에 교착 상태가 생기지 않게 하고 응답의 수를 다시 읽지 않게 한다. 글이 없으면 빈 값.
     */
    @Query(value = "SELECT like_count FROM posts WHERE id = :postId FOR UPDATE", nativeQuery = true)
    Optional<Integer> lockLikeCount(@Param("postId") Long postId);

    boolean existsByUserIdAndPostId(Long userId, Long postId);
}
