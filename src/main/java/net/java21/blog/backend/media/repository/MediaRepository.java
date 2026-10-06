package net.java21.blog.backend.media.repository;

import java.util.Optional;

import net.java21.blog.backend.media.domain.Media;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaRepository extends JpaRepository<Media, Long> {

    Optional<Media> findByMediaKey(String mediaKey);
}
