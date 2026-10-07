package net.java21.blog.backend.spam.repository;

import java.util.Optional;

import net.java21.blog.backend.spam.domain.BannedWord;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 금칙어 저장소(005 T020·T075). 매처 캐시는 {@code findAll}(관리자 정보 없이 한 번)로 읽는다. */
public interface BannedWordRepository extends JpaRepository<BannedWord, Long> {

    boolean existsByWord(String word);

    /** 관리 응답용: 등록 관리자와 함께 한 번에. */
    @EntityGraph(attributePaths = "createdBy")
    @Query("select w from BannedWord w where w.id = :id")
    Optional<BannedWord> findWithCreator(@Param("id") Long id);
}
