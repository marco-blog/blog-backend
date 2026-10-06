package net.java21.blog.backend.releasenote.repository;

import net.java21.blog.backend.releasenote.domain.ReleaseNoteContent;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContentId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseNoteContentRepository extends JpaRepository<ReleaseNoteContent, ReleaseNoteContentId> {
}
