package net.java21.blog.backend.media.repository;

import java.util.Collection;
import java.util.List;

import net.java21.blog.backend.media.domain.PostMedia;
import net.java21.blog.backend.media.domain.PostMediaId;
import net.java21.blog.backend.media.domain.PostMediaSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 글-이미지 참조(post_media). 바꿀 때는 출처(PUBLISHED·DRAFT)별로 지우고 다시 넣는다. */
public interface PostMediaRepository extends JpaRepository<PostMedia, PostMediaId> {

    /** 이 글의 한 출처가 참조하는 이미지 id. 쿼리 1회. */
    @Query("select pm.id.mediaId from PostMedia pm where pm.id.postId = :postId and pm.id.source = :source")
    List<Long> findMediaIds(@Param("postId") Long postId, @Param("source") PostMediaSource source);

    /** 이 글의 한 출처 참조를 모두 지운다. DELETE 1회. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PostMedia pm where pm.id.postId = :postId and pm.id.source = :source")
    int deleteByPostAndSource(@Param("postId") Long postId, @Param("source") PostMediaSource source);

    /** 여러 글이 참조하는 이미지 id(중복 없이). 쿼리 1회. */
    @Query("select distinct pm.id.mediaId from PostMedia pm where pm.id.postId in :postIds")
    List<Long> findMediaIdsOfPosts(@Param("postIds") Collection<Long> postIds);

    /** 여러 글의 참조를 모두 지운다(영구 삭제). DELETE 1회. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PostMedia pm where pm.id.postId in :postIds")
    int deleteByPosts(@Param("postIds") Collection<Long> postIds);
}
