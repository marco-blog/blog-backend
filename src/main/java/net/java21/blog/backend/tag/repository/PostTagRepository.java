package net.java21.blog.backend.tag.repository;

import net.java21.blog.backend.tag.domain.PostTag;
import net.java21.blog.backend.tag.domain.PostTagId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostTagRepository extends JpaRepository<PostTag, PostTagId> {

    /** 글의 태그 연결을 모두 지운다(발행 때 교체). DELETE 1회. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PostTag pt where pt.post.id = :postId")
    int deleteByPostId(@Param("postId") Long postId);
}
