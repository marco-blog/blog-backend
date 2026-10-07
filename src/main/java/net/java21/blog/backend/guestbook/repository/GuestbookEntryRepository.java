package net.java21.blog.backend.guestbook.repository;

import java.util.Optional;

import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestbookEntryRepository extends JpaRepository<GuestbookEntry, Long> {

    /** 방명록 글과 그 블로그·블로그 주인, 부모 글을 함께 읽는다(쿼리 1회). 권한 판단은 서비스가 한다. */
    @Query("select e from GuestbookEntry e join fetch e.blog b join fetch b.user left join fetch e.parent"
            + " where e.id = :id")
    Optional<GuestbookEntry> findWithBlogAndOwner(@Param("id") Long id);

    /** 이 글에 달린 답글이 있는지. */
    boolean existsByParentId(Long parentId);

    /** 이 글에 달린 답글 중 {@code exceptId}가 아닌 것이 있는지. */
    boolean existsByParentIdAndIdNot(Long parentId, Long exceptId);
}
