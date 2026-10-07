package net.java21.blog.backend.releasenote.repository;

import java.util.List;

import net.java21.blog.backend.releasenote.domain.ReleaseNoteContent;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReleaseNoteContentRepository extends JpaRepository<ReleaseNoteContent, ReleaseNoteContentId> {

    @Query("select c from ReleaseNoteContent c where c.releaseNote.id = :noteId")
    List<ReleaseNoteContent> findByNoteId(@Param("noteId") Long noteId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ReleaseNoteContent c where c.releaseNote.id = :noteId")
    int deleteByNoteId(@Param("noteId") Long noteId);
}
