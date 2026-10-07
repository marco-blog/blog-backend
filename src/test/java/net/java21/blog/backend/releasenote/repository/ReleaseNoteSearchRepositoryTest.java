package net.java21.blog.backend.releasenote.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContent;
import net.java21.blog.backend.search.SearchProperties;
import net.java21.blog.backend.search.service.SearchQueryParser;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 릴리스 노트 검색(003 T106, FR-165): {@code MATCH(title, content_text)}로 (노트, 언어) 일치, 한글 2자 낱말, 두 낱말 AND, 초안 제외.
 * InnoDB FULLTEXT는 커밋된 행만 찾으므로 트랜잭션 없이 저장하고 끝에 지운다. 실행마다 새로 만든 낱말·버전을 쓴다.
 */
@MySqlRepositoryTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ReleaseNoteSearchRepository.class)
class ReleaseNoteSearchRepositoryTest {

    private static final String LETTERS = "bcdfghjklmnopqrstuvwxyz";

    @Autowired
    private EntityManager em;
    @Autowired
    private ReleaseNoteSearchRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final SearchQueryParser parser = new SearchQueryParser(new SearchProperties(5, 2));
    private final List<Long> noteIds = new ArrayList<>();
    private Long userId;
    private int major;

    @BeforeEach
    void setUp() {
        major = ThreadLocalRandom.current().nextInt(100_000, 999_999);
    }

    @AfterEach
    void cleanUp() {
        noteIds.forEach(id -> {
            jdbc.update("DELETE FROM release_note_revisions WHERE release_note_id = ?", id);
            jdbc.update("DELETE FROM release_note_contents WHERE release_note_id = ?", id);
            jdbc.update("DELETE FROM release_notes WHERE id = ?", id);
        });
        if (userId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void matchesPerLanguageWithKoreanTwoLetterWordsAndAnd() {
        String hangul = randomHangul();
        String word = randomWord();
        String other = randomWord();
        long[] ids = new long[4];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User admin = user();
            ids[0] = note(admin, 0, true, Map.of("ko", "새 " + hangul + " 기능", "en", "nothing here")).getId();
            ids[1] = note(admin, 1, true, Map.of("ko", "본문", "en", "body " + word + " and " + other)).getId();
            ids[2] = note(admin, 2, true, Map.of("ko", word + " 하나만")).getId();
            ids[3] = note(admin, 3, false, Map.of("ko", "초안 " + hangul + " " + word)).getId();
            em.flush();
        });

        Map<Long, Set<String>> korean = mine(repository.findMatches(parser.parse(hangul).booleanQuery()));
        assertThat(korean).containsOnlyKeys(ids[0]);
        assertThat(korean.get(ids[0])).containsExactly("ko");

        Map<Long, Set<String>> single = mine(repository.findMatches(parser.parse(word).booleanQuery()));
        assertThat(single).containsOnlyKeys(ids[1], ids[2]);
        assertThat(single.get(ids[1])).containsExactly("en");

        Map<Long, Set<String>> both = mine(repository.findMatches(parser.parse(word + " " + other).booleanQuery()));
        assertThat(both).containsOnlyKeys(ids[1]);
    }

    private Map<Long, Set<String>> mine(Map<Long, Set<String>> matches) {
        matches.keySet().retainAll(noteIds);
        return matches;
    }

    private User user() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        User user = new User("rn-" + unique + "@example.com", (unique + unique).substring(0, 64), "$2a$hash", "관리자",
                null, null, "2026-10-06", Instant.parse("2026-10-01T00:00:00Z"));
        em.persist(user);
        userId = user.getId();
        return user;
    }

    private ReleaseNote note(User admin, int patch, boolean published, Map<String, String> texts) {
        ReleaseNote note = new ReleaseNote(major + ".0." + patch, major, 0, patch, java.time.LocalDate.of(2026, 10, 6),
                admin);
        em.persist(note);
        texts.forEach((lang, text) -> {
            ReleaseNoteContent content = new ReleaseNoteContent(note, lang);
            content.write("제목 " + lang, text, "<p>" + text + "</p>", text, List.of());
            em.persist(content);
        });
        if (published) {
            note.publish(Instant.parse("2026-10-02T00:00:00Z"), admin);
        }
        em.flush();
        noteIds.add(note.getId());
        return note;
    }

    private static String randomWord() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(LETTERS.charAt(ThreadLocalRandom.current().nextInt(LETTERS.length())));
        }
        return sb.toString();
    }

    private static String randomHangul() {
        return new String(new char[] {(char) ThreadLocalRandom.current().nextInt(0xB000, 0xD000),
                (char) ThreadLocalRandom.current().nextInt(0xB000, 0xD000)});
    }
}
