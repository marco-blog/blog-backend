package net.java21.blog.backend.releasenote.repository;

import static net.java21.blog.backend.releasenote.domain.QReleaseNote.releaseNote;
import static net.java21.blog.backend.releasenote.domain.QReleaseNoteContent.releaseNoteContent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.common.persistence.MySqlFullTextFunctions;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;
import org.springframework.stereotype.Repository;

/**
 * 릴리스 노트 검색(003 FR-165, 006 data-model "읽기 쿼리"): 게시 노트의 언어판 중 {@code MATCH(title, content_text)}가 맞는
 * (노트, 언어) 쌍. 어느 언어판을 보여줄지는 서비스가 정하고, 그 언어판이 맞은 노트만 결과에 넣는다. MySQL 전용(FULLTEXT ngram).
 */
@Repository
public class ReleaseNoteSearchRepository {

    private final JPAQueryFactory queryFactory;

    public ReleaseNoteSearchRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 노트 id → 일치한 언어들. 쿼리 1회. */
    public Map<Long, Set<String>> findMatches(String booleanQuery) {
        Map<Long, Set<String>> matches = new LinkedHashMap<>();
        queryFactory.select(releaseNoteContent.releaseNote.id, releaseNoteContent.id.lang)
                .from(releaseNoteContent)
                .join(releaseNoteContent.releaseNote, releaseNote)
                .where(releaseNote.status.eq(ReleaseNoteStatus.PUBLISHED),
                        MySqlFullTextFunctions.matchTitleContent(releaseNoteContent.title,
                                releaseNoteContent.contentText, booleanQuery).gt(0.0))
                .fetch()
                .forEach(t -> matches.computeIfAbsent(t.get(0, Long.class), id -> new LinkedHashSet<>())
                        .add(t.get(1, String.class)));
        return matches;
    }
}
