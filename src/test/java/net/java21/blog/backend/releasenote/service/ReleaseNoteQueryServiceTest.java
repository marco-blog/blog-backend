package net.java21.blog.backend.releasenote.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.content.HtmlSanitizerPolicy;
import net.java21.blog.backend.content.VideoEmbedTransformer;
import net.java21.blog.backend.releasenote.ReleaseNoteProperties;
import net.java21.blog.backend.releasenote.SemVer;
import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContent;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContentId;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteRevision;
import net.java21.blog.backend.releasenote.domain.RevisionContent;
import net.java21.blog.backend.releasenote.domain.TocEntry;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteDetailResponse;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteListResponse;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteSearchHit;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteContentRepository;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteQueryRepository;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteRevisionRepository;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteSearchRepository;
import net.java21.blog.backend.releasenote.repository.RevisionRow;
import net.java21.blog.backend.search.SearchProperties;
import net.java21.blog.backend.search.service.SearchQueryParser;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 릴리스 노트 독자 조회(003 T108, FR-161·162·164~166): 언어판 대체, 포털 카드 14일 경계, 노트 없음, 상세·수정본 404, 수정본 수,
 * 검색(고른 언어판에서만 일치, 버전 내림차순 페이지, 일치 부분 160자 이내, q 길이 400).
 */
@ExtendWith(MockitoExtension.class)
class ReleaseNoteQueryServiceTest {

    private static final Instant PUBLISHED = Instant.parse("2026-10-01T00:00:00Z");

    @Mock
    private ReleaseNoteQueryRepository queryRepository;
    @Mock
    private ReleaseNoteSearchRepository searchRepository;
    @Mock
    private ReleaseNoteContentRepository contentRepository;
    @Mock
    private ReleaseNoteRevisionRepository revisionRepository;

    private final MutableClock clock = new MutableClock(PUBLISHED.plus(Duration.ofDays(1)));
    private ReleaseNoteQueryService service;
    private User admin;
    private ReleaseNote v130;
    private ReleaseNote v121;
    private ReleaseNote v120;

    @BeforeEach
    void setUp() {
        service = new ReleaseNoteQueryService(queryRepository, searchRepository, contentRepository,
                revisionRepository, new ReleaseNoteRenderer(new HtmlSanitizerPolicy(), new VideoEmbedTransformer()),
                new SearchQueryParser(new SearchProperties(5, 2)), new ReleaseNoteProperties(Duration.ofDays(14)),
                clock);
        admin = TestEntities.user(9L);
        v130 = note(3L, "1.3.0");
        v121 = note(2L, "1.2.1");
        v120 = note(1L, "1.2.0");
        lenient().when(queryRepository.findTitles(anyCollection())).thenReturn(Map.of(
                3L, Map.of("ko", "일삼영"),
                2L, Map.of("ko", "일이일"),
                1L, Map.of("ko", "일이영", "en", "One two zero")));
    }

    @Test
    void listFallsBackPerNoteAndShowsCardWithinFourteenDays() {
        when(queryRepository.findPublished()).thenReturn(List.of(v130, v121, v120));

        ReleaseNoteListResponse list = service.list("ja");

        assertThat(list.items()).extracting(s -> s.version() + ":" + s.lang() + ":" + s.title())
                .containsExactly("1.3.0:ko:일삼영", "1.2.1:ko:일이일", "1.2.0:en:One two zero");
        assertThat(list.portalCard().version()).isEqualTo("1.3.0");
        assertThat(list.portalCard().firstPublishedAt()).isEqualTo(PUBLISHED);

        clock.set(PUBLISHED.plus(Duration.ofDays(14)).minusMillis(1));
        assertThat(service.list("ko").portalCard()).isNotNull();
        clock.set(PUBLISHED.plus(Duration.ofDays(14)));
        assertThat(service.list("ko").portalCard()).isNull();
    }

    @Test
    void emptyListHasNoCard() {
        when(queryRepository.findPublished()).thenReturn(List.of());

        assertThat(service.list("ko")).isEqualTo(new ReleaseNoteListResponse(List.of(), null));
        verify(queryRepository, never()).findTitles(anyCollection());
    }

    @Test
    void detailUsesFallbackEditionAndNeighbours() {
        when(queryRepository.findPublished("1.2.0")).thenReturn(v120);
        when(queryRepository.findNext(new SemVer(1, 2, 0))).thenReturn(v121);
        ReleaseNoteContent en = new ReleaseNoteContent(v120, "en");
        en.write("One two zero", "## New", "<h2 id=\"new\">New</h2>", "New", List.of(new TocEntry(2, "New", "new")));
        when(contentRepository.findById(new ReleaseNoteContentId(1L, "en"))).thenReturn(Optional.of(en));
        when(queryRepository.countRevisionsSince(1L, 1)).thenReturn(3L);

        ReleaseNoteDetailResponse detail = service.detail("1.2.0", "ja");

        assertThat(detail.requestedLang()).isEqualTo("ja");
        assertThat(detail.lang()).isEqualTo("en");
        assertThat(detail.title()).isEqualTo("One two zero");
        assertThat(detail.contentHtml()).contains("id=\"new\"");
        assertThat(detail.toc()).containsExactly(new TocEntry(2, "New", "new"));
        assertThat(detail.prev()).isNull();
        assertThat(detail.next().version()).isEqualTo("1.2.1");
        assertThat(detail.next().title()).isEqualTo("일이일");
        assertThat(detail.revisionCount()).isEqualTo(3);
        assertThat(detail.releaseDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void draftsMissingAndMalformedVersionsAre404() {
        when(queryRepository.findPublished("2.0.0")).thenReturn(null);
        expect404(() -> service.detail("2.0.0", "ko"));
        expect404(() -> service.detail("v1.2", "ko"));
        expect404(() -> service.revisions("2.0.0"));
        verify(queryRepository, never()).findPublished("v1.2");
    }

    @Test
    void revisionsStartAtFirstPublishedRevision() {
        ReflectionTestUtils.setField(v120, "firstPublishedRevisionNo", 2);
        when(queryRepository.findPublished("1.2.0")).thenReturn(v120);
        when(queryRepository.findRevisions(1L, 2)).thenReturn(List.of(
                new RevisionRow(3, PUBLISHED.plusSeconds(60), null, 9L, "관리자"),
                new RevisionRow(2, PUBLISHED, null, 9L, "관리자")));

        assertThat(service.revisions("1.2.0")).extracting(r -> r.revisionNo() + "@" + r.editedAt())
                .containsExactly("3@" + PUBLISHED.plusSeconds(60), "2@" + PUBLISHED);
        expect404(() -> service.revision("1.2.0", 1, "ko"));
    }

    @Test
    void oldRevisionIsRenderedAgainFromStoredMarkdown() {
        when(queryRepository.findPublished("1.2.0")).thenReturn(v120);
        ReleaseNoteRevision revision = new ReleaseNoteRevision(v120, admin, Map.of(
                "ko", new RevisionContent("예전", "## 예전 기능"),
                "ja", new RevisionContent("昔", "## 昔の機能")));
        TestEntities.with(revision, "createdAt", PUBLISHED);
        when(revisionRepository.findByReleaseNoteIdAndRevisionNo(1L, 1)).thenReturn(Optional.of(revision));
        when(revisionRepository.findByReleaseNoteIdAndRevisionNo(1L, 5)).thenReturn(Optional.empty());
        when(queryRepository.countRevisionsSince(1L, 1)).thenReturn(2L);

        ReleaseNoteDetailResponse old = service.revision("1.2.0", 1, "ja");

        assertThat(old.lang()).isEqualTo("ja");
        assertThat(old.title()).isEqualTo("昔");
        assertThat(old.contentHtml()).contains("<h2 id=\"昔の機能\">");
        assertThat(old.revisionNo()).isEqualTo(1);
        assertThat(old.updatedAt()).isEqualTo(PUBLISHED);
        assertThat(service.revision("1.2.0", 1, "en").lang()).isEqualTo("ko");
        expect404(() -> service.revision("1.2.0", 5, "ko"));
    }

    @Test
    void searchMatchesOnlyTheShownEditionNewestFirst() {
        when(queryRepository.findPublished()).thenReturn(List.of(v130, v121, v120));
        // 1.3.0: ko만 있어 ko가 맞으면 결과. 1.2.0: en을 보여주는데 ko만 맞음 → 빠짐. 1.2.1: 맞지 않음.
        when(searchRepository.findMatches(anyString())).thenReturn(Map.of(3L, Set.of("ko"), 1L, Set.of("ko")));
        String text = "가".repeat(100) + " 검색어 " + "나".repeat(100);
        when(queryRepository.findTexts(List.of(3L))).thenReturn(Map.of(3L, Map.of("ko",
                new String[] {"일삼영", text})));

        Page<ReleaseNoteSearchHit> page = service.search("검색어", "en", PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        ReleaseNoteSearchHit hit = page.getContent().get(0);
        assertThat(hit.version()).isEqualTo("1.3.0");
        assertThat(hit.lang()).isEqualTo("ko");
        assertThat(hit.snippet()).contains("검색어").hasSizeLessThanOrEqualTo(160);
        assertThat(service.search("검색어", "ko", PageRequest.of(5, 20)).getContent()).isEmpty();
    }

    @Test
    void searchValidatesQueryAndHandlesNoNotes() {
        assertThatThrownBy(() -> service.search("a", "ko", PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors().get(0).field()).isEqualTo("q");
                });
        assertThatThrownBy(() -> service.search("가".repeat(101), "ko", PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class);
        when(queryRepository.findPublished()).thenReturn(List.of());
        assertThat(service.search("검색어", "ko", PageRequest.of(0, 20)).getTotalElements()).isZero();
        verify(searchRepository, never()).findMatches(any());
    }

    @Test
    void snippetWindow() {
        assertThat(ReleaseNoteQueryService.snippet("", List.of("a"))).isEmpty();
        assertThat(ReleaseNoteQueryService.snippet(null, List.of("a"))).isEmpty();
        assertThat(ReleaseNoteQueryService.snippet("앞부분 본문", List.of("없음"))).isEqualTo("앞부분 본문");
        String text = "x".repeat(300) + "Target" + "y".repeat(300);
        String snippet = ReleaseNoteQueryService.snippet(text, List.of("target"));
        assertThat(snippet).contains("Target").hasSize(160).startsWith("x");
    }

    private ReleaseNote note(long id, String version) {
        SemVer v = SemVer.parse(version).orElseThrow();
        ReleaseNote note = new ReleaseNote(version, v.major(), v.minor(), v.patch(), LocalDate.of(2026, 10, 6),
                admin);
        ReflectionTestUtils.setField(note, "id", id);
        note.publish(PUBLISHED, admin);
        return note;
    }

    private static void expect404(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RELEASE_NOTE_NOT_FOUND));
    }
}
