package net.java21.blog.backend.spam.repository;

import net.java21.blog.backend.spam.domain.BannedWord;
import org.springframework.data.jpa.repository.JpaRepository;

/** 금칙어 저장소(005 T020). 관리 화면 목록·캐시 다시 읽기는 US2가 더한다. */
public interface BannedWordRepository extends JpaRepository<BannedWord, Long> {

    boolean existsByWord(String word);
}
