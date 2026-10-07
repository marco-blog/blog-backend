package net.java21.blog.backend.releasenote.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.releasenote.ReleaseNoteLanguage;
import net.java21.blog.backend.releasenote.ReleaseNoteProperties;
import net.java21.blog.backend.releasenote.SemVer;
import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContent;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContentId;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteRevision;
import net.java21.blog.backend.releasenote.domain.RevisionContent;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteDetailResponse;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteListResponse;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteRevisionItem;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteSearchHit;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteSummary;
import net.java21.blog.backend.releasenote.dto.VersionRef;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteContentRepository;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteQueryRepository;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteRevisionRepository;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteSearchRepository;
import net.java21.blog.backend.search.service.SearchQuery;
import net.java21.blog.backend.search.service.SearchQueryParser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 릴리스 노트 독자 API(003 FR-161~166). 게시(PUBLISHED) 노트만 다루고 초안·없는 버전은 404 {@code RELEASE_NOTE_NOT_FOUND}다.
 * 언어판은 요청 → en → ko 순으로 대체하며 제목과 본문을 함께 고른다({@link ReleaseNoteLanguage}).
 */
@Service
public class ReleaseNoteQueryService {

    static final int SNIPPET_LENGTH = 160;
    private static final int SNIPPET_LEAD = 50;

    private final ReleaseNoteQueryRepository queryRepository;
    private final ReleaseNoteSearchRepository searchRepository;
    private final ReleaseNoteContentRepository contentRepository;
    private final ReleaseNoteRevisionRepository revisionRepository;
    private final ReleaseNoteRenderer renderer;
    private final SearchQueryParser parser;
    private final ReleaseNoteProperties properties;
    private final Clock clock;

    public ReleaseNoteQueryService(ReleaseNoteQueryRepository queryRepository,
            ReleaseNoteSearchRepository searchRepository, ReleaseNoteContentRepository contentRepository,
            ReleaseNoteRevisionRepository revisionRepository, ReleaseNoteRenderer renderer, SearchQueryParser parser,
            ReleaseNoteProperties properties, Clock clock) {
        this.queryRepository = queryRepository;
        this.searchRepository = searchRepository;
        this.contentRepository = contentRepository;
        this.revisionRepository = revisionRepository;
        this.renderer = renderer;
        this.parser = parser;
        this.properties = properties;
        this.clock = clock;
    }

    /** 게시 노트 전체와 포털 카드. 쿼리 2회(노트, 제목). */
    @Transactional(readOnly = true)
    public ReleaseNoteListResponse list(String lang) {
        List<ReleaseNote> notes = queryRepository.findPublished();
        if (notes.isEmpty()) {
            return new ReleaseNoteListResponse(List.of(), null);
        }
        Map<Long, Map<String, String>> titles = queryRepository.findTitles(notes.stream().map(ReleaseNote::getId)
                .toList());
        List<ReleaseNoteSummary> items = notes.stream().map(n -> summary(n, titles.get(n.getId()), lang))
                .filter(Objects::nonNull).toList();
        ReleaseNote latest = notes.get(0);
        boolean card = latest.getFirstPublishedAt() != null
                && clock.instant().isBefore(latest.getFirstPublishedAt().plus(properties.portalCardDays()));
        return new ReleaseNoteListResponse(items, card && !items.isEmpty() ? items.get(0) : null);
    }

    /** 버전 상세. 쿼리 6회 이내(노트, 이전, 다음, 제목, 언어판, 수정본 수). */
    @Transactional(readOnly = true)
    public ReleaseNoteDetailResponse detail(String version, String lang) {
        ReleaseNote note = requirePublished(version);
        SemVer semVer = SemVer.parse(note.getVersion()).orElseThrow();
        ReleaseNote prev = queryRepository.findPrevious(semVer);
        ReleaseNote next = queryRepository.findNext(semVer);
        Map<Long, Map<String, String>> titles = queryRepository.findTitles(Stream.of(note, prev, next)
                .filter(Objects::nonNull).map(ReleaseNote::getId).toList());
        String chosen = ReleaseNoteLanguage.pick(titles.getOrDefault(note.getId(), Map.of()).keySet(), lang);
        ReleaseNoteContent content = chosen == null ? null
                : contentRepository.findById(new ReleaseNoteContentId(note.getId(), chosen)).orElse(null);
        if (content == null) {
            throw notFound(version);
        }
        return new ReleaseNoteDetailResponse(note.getVersion(), note.getReleaseDate(), note.getFirstPublishedAt(),
                note.getUpdatedAt(), lang, chosen, content.getTitle(), content.getContentHtml(), content.getToc(),
                ref(prev, titles, lang), ref(next, titles, lang), revisionCount(note), note.getCurrentRevisionNo());
    }

    /** 독자 "수정 이력": 처음 게시한 수정본부터, 새 것 먼저. 수정한 관리자는 주지 않는다. */
    @Transactional(readOnly = true)
    public List<ReleaseNoteRevisionItem> revisions(String version) {
        ReleaseNote note = requirePublished(version);
        return queryRepository.findRevisions(note.getId(), firstRevision(note)).stream()
                .map(r -> new ReleaseNoteRevisionItem(r.revisionNo(), r.editedAt())).toList();
    }

    /** 이전 수정본의 내용(저장된 Markdown을 다시 변환). 처음 게시 전 수정본·없는 번호는 404. */
    @Transactional(readOnly = true)
    public ReleaseNoteDetailResponse revision(String version, int revisionNo, String lang) {
        ReleaseNote note = requirePublished(version);
        if (revisionNo < firstRevision(note)) {
            throw notFound(version);
        }
        ReleaseNoteRevision revision = revisionRepository.findByReleaseNoteIdAndRevisionNo(note.getId(), revisionNo)
                .orElseThrow(() -> notFound(version));
        Map<String, RevisionContent> contents = revision.getContents();
        String chosen = ReleaseNoteLanguage.pick(contents.keySet(), lang);
        if (chosen == null) {
            throw notFound(version);
        }
        RevisionContent content = contents.get(chosen);
        RenderedReleaseNote rendered = renderer.render(content.contentMarkdown());
        SemVer semVer = SemVer.parse(note.getVersion()).orElseThrow();
        ReleaseNote prev = queryRepository.findPrevious(semVer);
        ReleaseNote next = queryRepository.findNext(semVer);
        Map<Long, Map<String, String>> titles = queryRepository.findTitles(Stream.of(prev, next)
                .filter(Objects::nonNull).map(ReleaseNote::getId).toList());
        return new ReleaseNoteDetailResponse(note.getVersion(), revision.getReleaseDate(), note.getFirstPublishedAt(),
                revision.getCreatedAt(), lang, chosen, content.title(), rendered.html(), rendered.toc(),
                ref(prev, titles, lang), ref(next, titles, lang), revisionCount(note), revision.getRevisionNo());
    }

    /**
     * 검색(003 FR-165): 노트마다 보여줄 언어판 하나를 고르고 그 언어판이 일치한 노트만, 버전 내림차순. 노트 수가 적어(수십~수백) 페이지는
     * 메모리에서 나눈다. 쿼리 4회(노트, 제목, 일치, 텍스트).
     */
    @Transactional(readOnly = true)
    public Page<ReleaseNoteSearchHit> search(String q, String lang, Pageable pageable) {
        SearchQuery query = parser.parse(q);
        List<ReleaseNote> notes = queryRepository.findPublished();
        if (notes.isEmpty()) {
            return Page.empty(pageable);
        }
        Map<Long, Map<String, String>> titles = queryRepository.findTitles(notes.stream().map(ReleaseNote::getId)
                .toList());
        Map<Long, Set<String>> matches = searchRepository.findMatches(query.booleanQuery());
        List<ReleaseNote> hits = new ArrayList<>();
        for (ReleaseNote note : notes) {
            String chosen = ReleaseNoteLanguage.pick(titles.getOrDefault(note.getId(), Map.of()).keySet(), lang);
            if (chosen != null && matches.getOrDefault(note.getId(), Set.of()).contains(chosen)) {
                hits.add(note);
            }
        }
        int from = (int) Math.min(pageable.getOffset(), hits.size());
        List<ReleaseNote> page = hits.subList(from, Math.min(from + pageable.getPageSize(), hits.size()));
        Map<Long, Map<String, String[]>> texts = queryRepository.findTexts(page.stream().map(ReleaseNote::getId)
                .toList());
        List<ReleaseNoteSearchHit> content = page.stream().map(note -> {
            String chosen = ReleaseNoteLanguage.pick(titles.get(note.getId()).keySet(), lang);
            String[] text = texts.get(note.getId()).get(chosen);
            return new ReleaseNoteSearchHit(note.getVersion(), text[0], snippet(text[1], query.terms()),
                    note.getReleaseDate(), chosen);
        }).toList();
        return new PageImpl<>(content, pageable, hits.size());
    }

    /** 일치 낱말 중 본문에서 가장 먼저 나오는 곳의 앞뒤(최대 160자). 본문에 없으면(제목만 일치) 본문 앞부분. */
    static String snippet(String text, List<String> terms) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int first = -1;
        for (String term : terms) {
            int at = lower.indexOf(term.toLowerCase(Locale.ROOT));
            if (at >= 0 && (first < 0 || at < first)) {
                first = at;
            }
        }
        int start = Math.max(0, first - SNIPPET_LEAD);
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) {
            start--;
        }
        int end = Math.min(text.length(), start + SNIPPET_LENGTH);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end).strip();
    }

    private ReleaseNote requirePublished(String version) {
        if (SemVer.parse(version).isEmpty()) {
            throw notFound(version);
        }
        ReleaseNote note = queryRepository.findPublished(version);
        if (note == null) {
            throw notFound(version);
        }
        return note;
    }

    private long revisionCount(ReleaseNote note) {
        return queryRepository.countRevisionsSince(note.getId(), firstRevision(note));
    }

    private static int firstRevision(ReleaseNote note) {
        return note.getFirstPublishedRevisionNo() == null ? 1 : note.getFirstPublishedRevisionNo();
    }

    private static ReleaseNoteSummary summary(ReleaseNote note, Map<String, String> titles, String lang) {
        if (titles == null) {
            return null;
        }
        String chosen = ReleaseNoteLanguage.pick(titles.keySet(), lang);
        return chosen == null ? null : new ReleaseNoteSummary(note.getVersion(), titles.get(chosen),
                note.getReleaseDate(), note.getFirstPublishedAt(), chosen);
    }

    private static VersionRef ref(ReleaseNote note, Map<Long, Map<String, String>> titles, String lang) {
        if (note == null) {
            return null;
        }
        Map<String, String> byLang = titles.getOrDefault(note.getId(), Map.of());
        String chosen = ReleaseNoteLanguage.pick(byLang.keySet(), lang);
        return new VersionRef(note.getVersion(), chosen == null ? null : byLang.get(chosen));
    }

    private static BusinessException notFound(String version) {
        return new BusinessException(ErrorCode.RELEASE_NOTE_NOT_FOUND, "Release note not found: " + version);
    }
}
