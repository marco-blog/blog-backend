package net.java21.blog.backend.admin.releasenote;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.admin.releasenote.dto.AdminReleaseNoteResponse;
import net.java21.blog.backend.admin.releasenote.dto.AdminReleaseNoteSummary;
import net.java21.blog.backend.admin.releasenote.dto.AdminRevisionResponse;
import net.java21.blog.backend.admin.releasenote.dto.ContentWrite;
import net.java21.blog.backend.admin.releasenote.dto.PreviewResponse;
import net.java21.blog.backend.admin.releasenote.dto.ReleaseNoteWriteRequest;
import net.java21.blog.backend.admin.releasenote.dto.UpdateReleaseNoteRequest;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.releasenote.ReleaseNoteLanguage;
import net.java21.blog.backend.releasenote.SemVer;
import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContent;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteRevision;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;
import net.java21.blog.backend.releasenote.domain.RevisionContent;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteContentRepository;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteQueryRepository;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteRepository;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteRevisionRepository;
import net.java21.blog.backend.releasenote.service.ReleaseNoteRenderer;
import net.java21.blog.backend.releasenote.service.RenderedReleaseNote;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 릴리스 노트 관리(001 contracts "릴리스 노트 관리", 006 FR-167·168, data-model release_notes·contents·revisions).
 * <ul>
 *   <li>만들기: DRAFT, 수정본 1. 버전 중복 409 {@code RELEASE_NOTE_VERSION_TAKEN}.</li>
 *   <li>수정: {@code current_revision_no = baseRevisionNo}일 때만 + 1(조건부 UPDATE, 아니면 409
 *       {@code RELEASE_NOTE_REVISION_CONFLICT}). 언어판 교체(빠진 키는 삭제), 새 수정본. 게시 이력이 있으면 버전 변경 422
 *       {@code RELEASE_NOTE_VERSION_LOCKED}.</li>
 *   <li>게시·게시 중단: 수정본을 만들지 않는다. 이미 그 상태면 변화 없음(작업 기록·캐시 비우기도 없음).</li>
 *   <li>삭제: 한 번도 게시하지 않은 초안만(아니면 409 {@code RELEASE_NOTE_ONCE_PUBLISHED}), 언어판·수정본도 함께.</li>
 *   <li>모든 쓰기는 작업 기록({@code target_id} = id, {@code target_key} = 버전)과 커밋 뒤 포털 캐시 비우기(카드·배너).</li>
 * </ul>
 */
@Service
public class AdminReleaseNoteService {

    static final int TITLE_MAX = 200;
    static final int CONTENT_MAX = 100_000;

    private final ReleaseNoteRepository noteRepository;
    private final ReleaseNoteContentRepository contentRepository;
    private final ReleaseNoteRevisionRepository revisionRepository;
    private final ReleaseNoteQueryRepository queryRepository;
    private final ReleaseNoteRenderer renderer;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AdminReleaseNoteService(ReleaseNoteRepository noteRepository,
            ReleaseNoteContentRepository contentRepository, ReleaseNoteRevisionRepository revisionRepository,
            ReleaseNoteQueryRepository queryRepository, ReleaseNoteRenderer renderer, UserRepository userRepository,
            AdminAuditService auditService, ApplicationEventPublisher events, Clock clock) {
        this.noteRepository = noteRepository;
        this.contentRepository = contentRepository;
        this.revisionRepository = revisionRepository;
        this.queryRepository = queryRepository;
        this.renderer = renderer;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    /** 목록(버전 내림차순). {@code status}는 DRAFT·PUBLISHED 또는 생략. 쿼리 3회(목록, 수, 언어판). */
    @Transactional(readOnly = true)
    public Page<AdminReleaseNoteSummary> list(String status, Pageable pageable) {
        Page<ReleaseNote> page = queryRepository.findForAdmin(parseStatus(status), pageable);
        Map<Long, Map<String, String>> titles = queryRepository.findTitles(page.getContent().stream()
                .map(ReleaseNote::getId).toList());
        return page.map(note -> new AdminReleaseNoteSummary(note.getId(), note.getVersion(), note.getStatus(),
                note.getReleaseDate(), ordered(titles.getOrDefault(note.getId(), Map.of()).keySet()),
                note.getCurrentRevisionNo(), note.getFirstPublishedAt(), note.getPublishedAt(), note.getUpdatedAt()));
    }

    @Transactional(readOnly = true)
    public AdminReleaseNoteResponse get(long id) {
        return response(require(id));
    }

    @Transactional
    public AdminReleaseNoteResponse create(long adminId, ReleaseNoteWriteRequest request, String requestIp) {
        SemVer version = validate(request);
        if (noteRepository.existsByVersion(version.toString())) {
            throw versionTaken(version);
        }
        User admin = userRepository.getReferenceById(adminId);
        ReleaseNote note = noteRepository.saveAndFlush(new ReleaseNote(version.toString(), version.major(),
                version.minor(), version.patch(), request.releaseDate(), admin));
        writeContents(note, request.contents(), List.of());
        revisionRepository.save(new ReleaseNoteRevision(note, admin, revisionContents(request.contents())));
        auditService.record(adminId, AuditActions.RELEASE_NOTE_CREATE, AuditActions.TARGET_RELEASE_NOTE,
                note.getId(), note.getVersion(), null, values(note, request.contents().keySet()), null, requestIp);
        events.publishEvent(new PortalChangedEvent("release-note:create"));
        return response(note);
    }

    @Transactional
    public AdminReleaseNoteResponse update(long adminId, long id, UpdateReleaseNoteRequest request,
            String requestIp) {
        SemVer version = validate(request.write());
        if (request.baseRevisionNo() == null) {
            throw invalid(List.of(FieldError.of("baseRevisionNo", "REQUIRED")));
        }
        // 노트를 영속성 컨텍스트에 올리기 전에 잠금 값을 올린다(바뀐 행이 0이면 충돌 또는 없음).
        if (queryRepository.claimRevision(id, request.baseRevisionNo(), clock.instant()) == 0) {
            if (!noteRepository.existsById(id)) {
                throw notFound(id);
            }
            throw new BusinessException(ErrorCode.RELEASE_NOTE_REVISION_CONFLICT,
                    "Release note was saved by someone else: " + id);
        }
        ReleaseNote note = require(id);
        if (!note.getVersion().equals(version.toString())) {
            if (note.getFirstPublishedAt() != null) {
                throw new BusinessException(ErrorCode.RELEASE_NOTE_VERSION_LOCKED,
                        "Published release note version cannot change: " + note.getVersion());
            }
            if (noteRepository.existsByVersion(version.toString())) {
                throw versionTaken(version);
            }
        }
        List<ReleaseNoteContent> existing = contentRepository.findByNoteId(id);
        Map<String, Object> before = values(note, existing.stream().map(ReleaseNoteContent::getLang).toList());
        before.put("revisionNo", request.baseRevisionNo());
        User admin = userRepository.getReferenceById(adminId);
        note.edit(version.toString(), version.major(), version.minor(), version.patch(), request.releaseDate(),
                admin);
        writeContents(note, request.contents(), existing);
        noteRepository.flush();
        revisionRepository.save(new ReleaseNoteRevision(note, admin, revisionContents(request.contents())));
        Map<String, Object> after = values(note, request.contents().keySet());
        after.put("revisionNo", note.getCurrentRevisionNo());
        auditService.record(adminId, AuditActions.RELEASE_NOTE_UPDATE, AuditActions.TARGET_RELEASE_NOTE, id,
                note.getVersion(), before, after, null, requestIp);
        events.publishEvent(new PortalChangedEvent("release-note:update"));
        return response(note);
    }

    @Transactional
    public AdminReleaseNoteResponse publish(long adminId, long id, String requestIp) {
        ReleaseNote note = require(id);
        if (!note.isPublished()) {
            note.publish(clock.instant(), userRepository.getReferenceById(adminId));
            noteRepository.flush();
            auditService.record(adminId, AuditActions.RELEASE_NOTE_PUBLISH, AuditActions.TARGET_RELEASE_NOTE, id,
                    note.getVersion(), Map.of("status", ReleaseNoteStatus.DRAFT.name()),
                    Map.of("status", ReleaseNoteStatus.PUBLISHED.name()), null, requestIp);
            events.publishEvent(new PortalChangedEvent("release-note:publish"));
        }
        return response(note);
    }

    @Transactional
    public AdminReleaseNoteResponse unpublish(long adminId, long id, String requestIp) {
        ReleaseNote note = require(id);
        if (note.isPublished()) {
            note.unpublish(userRepository.getReferenceById(adminId));
            noteRepository.flush();
            auditService.record(adminId, AuditActions.RELEASE_NOTE_UNPUBLISH, AuditActions.TARGET_RELEASE_NOTE, id,
                    note.getVersion(), Map.of("status", ReleaseNoteStatus.PUBLISHED.name()),
                    Map.of("status", ReleaseNoteStatus.DRAFT.name()), null, requestIp);
            events.publishEvent(new PortalChangedEvent("release-note:unpublish"));
        }
        return response(note);
    }

    @Transactional
    public void delete(long adminId, long id, String requestIp) {
        ReleaseNote note = require(id);
        if (note.getFirstPublishedAt() != null) {
            throw new BusinessException(ErrorCode.RELEASE_NOTE_ONCE_PUBLISHED,
                    "Once published release note cannot be deleted: " + note.getVersion());
        }
        List<String> langs = contentRepository.findByNoteId(id).stream().map(ReleaseNoteContent::getLang).toList();
        Map<String, Object> before = values(note, langs);
        contentRepository.deleteByNoteId(id);
        revisionRepository.deleteByNoteId(id);
        noteRepository.delete(note);
        auditService.record(adminId, AuditActions.RELEASE_NOTE_DELETE, AuditActions.TARGET_RELEASE_NOTE, id,
                note.getVersion(), before, null, null, requestIp);
        events.publishEvent(new PortalChangedEvent("release-note:delete"));
    }

    /** 저장하지 않고 독자 화면과 같은 변환. */
    public PreviewResponse preview(String contentMarkdown) {
        RenderedReleaseNote rendered = renderer.render(contentMarkdown);
        return new PreviewResponse(rendered.html(), rendered.toc());
    }

    /** 수정본 목록(새 것 먼저, 수정한 관리자·당시 상태 포함). */
    @Transactional(readOnly = true)
    public List<AdminRevisionResponse> revisions(long id) {
        require(id);
        return queryRepository.findRevisions(id, null).stream()
                .map(r -> new AdminRevisionResponse(r.revisionNo(), new AdminRef(r.editedById(), r.editedByNickname()),
                        r.editedAt(), r.status(), null, null, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminRevisionResponse revision(long id, int revisionNo) {
        ReleaseNoteRevision revision = revisionRepository.findByReleaseNoteIdAndRevisionNo(id, revisionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.RELEASE_NOTE_NOT_FOUND,
                        "Release note revision not found: " + id + "/" + revisionNo));
        Map<String, ContentWrite> contents = new LinkedHashMap<>();
        revision.getContents().entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> ReleaseNoteLanguage.ALL.indexOf(a)
                        - ReleaseNoteLanguage.ALL.indexOf(b)))
                .forEach(e -> contents.put(e.getKey(), new ContentWrite(e.getValue().title(),
                        e.getValue().contentMarkdown())));
        User editor = revision.getEditedBy();
        return new AdminRevisionResponse(revision.getRevisionNo(), new AdminRef(editor.getId(), editor.getNickname()),
                revision.getCreatedAt(), revision.getStatus(), revision.getVersion(), revision.getReleaseDate(),
                contents);
    }

    /** 형식 검사(모든 오류를 한 번에). 통과하면 버전. */
    static SemVer validate(ReleaseNoteWriteRequest request) {
        List<FieldError> errors = new ArrayList<>();
        SemVer version = null;
        if (request.version() == null || request.version().isBlank()) {
            errors.add(FieldError.of("version", "REQUIRED"));
        } else {
            version = SemVer.parse(request.version()).orElse(null);
            if (version == null) {
                errors.add(FieldError.of("version", "INVALID_FORMAT"));
            }
        }
        if (request.releaseDate() == null) {
            errors.add(FieldError.of("releaseDate", "REQUIRED"));
        }
        Map<String, ContentWrite> contents = request.contents() == null ? Map.of() : request.contents();
        if (!contents.containsKey(ReleaseNoteLanguage.KO)) {
            errors.add(FieldError.of("contents.ko", "REQUIRED"));
        }
        for (Map.Entry<String, ContentWrite> entry : contents.entrySet()) {
            String field = "contents." + entry.getKey();
            if (!ReleaseNoteLanguage.ALL.contains(entry.getKey())) {
                errors.add(new FieldError(field, "INVALID", Map.of("allowed", ReleaseNoteLanguage.ALL)));
                continue;
            }
            ContentWrite content = entry.getValue();
            text(errors, field + ".title", content == null ? null : content.title(), TITLE_MAX);
            text(errors, field + ".contentMarkdown", content == null ? null : content.contentMarkdown(), CONTENT_MAX);
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return version;
    }

    private static void text(List<FieldError> errors, String field, String value, int max) {
        if (value == null || value.isBlank()) {
            errors.add(FieldError.of(field, "REQUIRED"));
        } else if (value.codePointCount(0, value.length()) > max) {
            errors.add(new FieldError(field, "TOO_LONG", Map.of("max", max)));
        }
    }

    /** 요청 언어판으로 바꾼다: 있으면 다시 쓰고, 없으면 만들고, 빠진 것은 지운다. */
    private void writeContents(ReleaseNote note, Map<String, ContentWrite> contents,
            List<ReleaseNoteContent> existing) {
        Map<String, ReleaseNoteContent> byLang = new LinkedHashMap<>();
        existing.forEach(c -> byLang.put(c.getLang(), c));
        for (ReleaseNoteContent content : existing) {
            if (!contents.containsKey(content.getLang())) {
                contentRepository.delete(content);
            }
        }
        for (String lang : ordered(contents.keySet())) {
            ContentWrite write = contents.get(lang);
            RenderedReleaseNote rendered = renderer.render(write.contentMarkdown());
            ReleaseNoteContent content = byLang.get(lang);
            boolean created = content == null;
            if (created) {
                content = new ReleaseNoteContent(note, lang);
            }
            content.write(write.title().strip(), write.contentMarkdown(), rendered.html(), rendered.text(),
                    rendered.toc());
            if (created) {
                contentRepository.save(content);
            }
        }
    }

    private AdminReleaseNoteResponse response(ReleaseNote note) {
        Map<String, ContentWrite> contents = new LinkedHashMap<>();
        contentRepository.findByNoteId(note.getId()).stream()
                .sorted((a, b) -> ReleaseNoteLanguage.ALL.indexOf(a.getLang())
                        - ReleaseNoteLanguage.ALL.indexOf(b.getLang()))
                .forEach(c -> contents.put(c.getLang(), new ContentWrite(c.getTitle(), c.getContentMarkdown())));
        return new AdminReleaseNoteResponse(note.getId(), note.getVersion(), note.getReleaseDate(), contents,
                note.getStatus(), note.getCurrentRevisionNo(), note.getFirstPublishedAt(), note.getPublishedAt(),
                ref(note.getCreatedBy()), ref(note.getUpdatedBy()), note.getCreatedAt(), note.getUpdatedAt());
    }

    private static AdminRef ref(User user) {
        return user == null ? null : new AdminRef(user.getId(), user.getNickname());
    }

    private static Map<String, RevisionContent> revisionContents(Map<String, ContentWrite> contents) {
        Map<String, RevisionContent> map = new LinkedHashMap<>();
        for (String lang : ordered(contents.keySet())) {
            ContentWrite write = contents.get(lang);
            map.put(lang, new RevisionContent(write.title().strip(), write.contentMarkdown()));
        }
        return map;
    }

    private static List<String> ordered(java.util.Collection<String> langs) {
        return ReleaseNoteLanguage.ALL.stream().filter(langs::contains).toList();
    }

    private static Map<String, Object> values(ReleaseNote note, java.util.Collection<String> langs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", note.getVersion());
        map.put("releaseDate", note.getReleaseDate().toString());
        map.put("langs", ordered(langs));
        return map;
    }

    private ReleaseNote require(long id) {
        return noteRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    static ReleaseNoteStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return Arrays.stream(ReleaseNoteStatus.values()).filter(s -> s.name().equals(status)).findFirst()
                .orElseThrow(() -> invalid(List.of(new FieldError("status", "INVALID", Map.of("allowed",
                        Arrays.stream(ReleaseNoteStatus.values()).map(Enum::name).toList())))));
    }

    private static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.RELEASE_NOTE_NOT_FOUND, "Release note not found: " + id);
    }

    private static BusinessException versionTaken(SemVer version) {
        return new BusinessException(ErrorCode.RELEASE_NOTE_VERSION_TAKEN, "Release note version taken: " + version);
    }

    private static BusinessException invalid(List<FieldError> errors) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", errors);
    }
}
