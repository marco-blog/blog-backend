package net.java21.blog.backend.releasenote.repository;

import java.util.Optional;

import net.java21.blog.backend.releasenote.domain.ReleaseNoteRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReleaseNoteRevisionRepository extends JpaRepository<ReleaseNoteRevision, Long> {

    Optional<ReleaseNoteRevision> findByReleaseNoteIdAndRevisionNo(Long releaseNoteId, int revisionNo);

    /** 한 번도 게시하지 않은 초안을 지울 때 수정본도 함께 지운다(006 data-model). */
    @Modifying(flushAutomatically = true)
    @Query("delete from ReleaseNoteRevision r where r.releaseNote.id = :noteId")
    int deleteByNoteId(@Param("noteId") Long noteId);
}
