package net.java21.blog.backend.trackback.repository;

import java.util.Optional;

import net.java21.blog.backend.trackback.domain.Trackback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 받은 트랙백 저장소(005 T019). 목록은 US3의 QueryDSL 저장소가 읽는다. */
public interface TrackbackRepository extends JpaRepository<Trackback, Long> {

    /** 트랙백과 받은 글·블로그·블로그 주인을 함께 읽는다(쿼리 1회). */
    @Query("select t from Trackback t join fetch t.post p join fetch p.blog b join fetch b.user where t.id = :id")
    Optional<Trackback> findWithPostAndOwner(@Param("id") Long id);

    boolean existsByPostIdAndSourceUrlHash(Long postId, String sourceUrlHash);
}
