package net.java21.blog.backend.releasenote.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.releasenote.SemVer;
import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteRevision;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;
import net.java21.blog.backend.releasenote.domain.RevisionContent;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 릴리스 노트 조회(003 T105): 게시 노트 SemVer 숫자 순(1.10.0 > 1.9.0), 초안 제외, 이전·다음, 최신, 언어판 제목 한 번에, 처음 게시
 * 수정본부터의 이력, 조건부 갱신의 영향 행 수, 쿼리 수 고정.
 */
@JpaRepositoryTest
@Import(ReleaseNoteQueryRepository.class)
class ReleaseNoteQueryRepositoryTest {

    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private ReleaseNoteQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private User admin;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        admin = fx.user("관리자");
    }

    @Test
    void publishedNotesAreOrderedNumericallyWithoutDrafts() {
        ReleaseNote v190 = fx.releaseNote(admin, "1.9.0", T, "본문", "ko");
        ReleaseNote v1100 = fx.releaseNote(admin, "1.10.0", T, "본문", "ko", "en");
        ReleaseNote v120 = fx.releaseNote(admin, "1.2.0", T, "본문", "ko");
        fx.releaseNote(admin, "2.0.0", null, "초안", "ko");
        fx.flushAndClear();

        queryCounter.reset();
        assertThat(repository.findPublished()).extracting(ReleaseNote::getVersion)
                .containsExactly("1.10.0", "1.9.0", "1.2.0");
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.findLatestPublished().getId()).isEqualTo(v1100.getId());
        assertThat(repository.findPublished("1.9.0").getId()).isEqualTo(v190.getId());
        assertThat(repository.findPublished("2.0.0")).isNull();
        assertThat(repository.findPrevious(new SemVer(1, 10, 0)).getId()).isEqualTo(v190.getId());
        assertThat(repository.findNext(new SemVer(1, 2, 0)).getId()).isEqualTo(v190.getId());
        assertThat(repository.findNext(new SemVer(1, 10, 0))).isNull();
        assertThat(repository.findPrevious(new SemVer(1, 2, 0))).isNull();

        queryCounter.reset();
        Map<Long, Map<String, String>> titles = repository.findTitles(List.of(v1100.getId(), v120.getId()));
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(titles.get(v1100.getId())).containsEntry("ko", "ko 1.10.0").containsEntry("en", "en 1.10.0");
        assertThat(titles.get(v120.getId())).containsOnlyKeys("ko");
        assertThat(repository.findTitles(List.of())).isEmpty();
        assertThat(repository.findTexts(List.of(v120.getId())).get(v120.getId()).get("ko"))
                .containsExactly("ko 1.2.0", "본문");
        assertThat(repository.findTexts(List.of())).isEmpty();
    }

    @Test
    void noPublishedNotes() {
        fx.releaseNote(admin, "1.0.0", null, "초안", "ko");
        fx.flushAndClear();

        assertThat(repository.findPublished()).isEmpty();
        assertThat(repository.findLatestPublished()).isNull();
    }

    @Test
    void revisionsSinceFirstPublishAndConditionalClaim() {
        ReleaseNote note = fx.releaseNote(admin, "1.2.0", null, "본문", "ko");
        fx.flushAndClear();
        // 수정본 2(초안), 게시, 수정본 3
        assertThat(repository.claimRevision(note.getId(), 1, T)).isEqualTo(1);
        assertThat(repository.claimRevision(note.getId(), 1, T)).isZero();
        fx.flushAndClear();
        ReleaseNote loaded = em.find(ReleaseNote.class, note.getId());
        assertThat(loaded.getCurrentRevisionNo()).isEqualTo(2);
        em.persist(new ReleaseNoteRevision(loaded, admin, Map.of("ko", new RevisionContent("둘", "둘"))));
        loaded.publish(T, admin);
        fx.flushAndClear();
        assertThat(repository.claimRevision(note.getId(), 2, T)).isEqualTo(1);
        fx.flushAndClear();
        loaded = em.find(ReleaseNote.class, note.getId());
        em.persist(new ReleaseNoteRevision(loaded, admin, Map.of("ko", new RevisionContent("셋", "셋"))));
        fx.flushAndClear();

        queryCounter.reset();
        List<RevisionRow> since = repository.findRevisions(note.getId(), 2);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(since).extracting(RevisionRow::revisionNo).containsExactly(3, 2);
        assertThat(since.get(0).status()).isEqualTo(ReleaseNoteStatus.PUBLISHED);
        assertThat(since.get(0).editedByNickname()).isEqualTo("관리자");
        assertThat(since.get(0).editedAt()).isNotNull();
        assertThat(repository.findRevisions(note.getId(), null)).extracting(RevisionRow::revisionNo)
                .containsExactly(3, 2, 1);
        assertThat(repository.countRevisionsSince(note.getId(), 2)).isEqualTo(2);
        assertThat(repository.claimRevision(999L, 1, T)).isZero();
    }

    @Test
    void adminListFiltersByStatusInTwoQueries() {
        fx.releaseNote(admin, "1.0.0", T, "본문", "ko");
        fx.releaseNote(admin, "1.1.0", null, "초안", "ko");
        fx.releaseNote(admin, "0.9.0", null, "초안", "ko");
        fx.flushAndClear();

        queryCounter.reset();
        Page<ReleaseNote> drafts = repository.findForAdmin(ReleaseNoteStatus.DRAFT, PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(drafts.getContent()).extracting(ReleaseNote::getVersion).containsExactly("1.1.0", "0.9.0");
        Page<ReleaseNote> all = repository.findForAdmin(null, PageRequest.of(0, 2));
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getContent()).extracting(ReleaseNote::getVersion).containsExactly("1.1.0", "1.0.0");
        assertThat(all.getContent().get(0).getReleaseDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }
}
