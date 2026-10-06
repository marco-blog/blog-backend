package net.java21.blog.backend.releasenote.repository;

import net.java21.blog.backend.releasenote.domain.ReleaseNote;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseNoteRepository extends JpaRepository<ReleaseNote, Long> {
}
