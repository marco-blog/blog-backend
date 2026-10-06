package net.java21.blog.backend.releasenote.repository;

import net.java21.blog.backend.releasenote.domain.ReleaseNoteRevision;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseNoteRevisionRepository extends JpaRepository<ReleaseNoteRevision, Long> {
}
