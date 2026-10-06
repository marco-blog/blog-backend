package net.java21.blog.backend.releasenote.repository;

import static net.java21.blog.backend.releasenote.domain.QReleaseNote.releaseNote;
import static net.java21.blog.backend.releasenote.domain.QReleaseNoteContent.releaseNoteContent;
import static net.java21.blog.backend.releasenote.domain.QReleaseNoteRevision.releaseNoteRevision;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.releasenote.SemVer;
import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 릴리스 노트 조회(003 T120, 006 data-model "읽기 쿼리"). 정렬은 (major, minor, patch) 숫자 순이다. 언어판 제목은 노트 id 목록으로 한 번에
 * 읽어 N+1이 없다. 저장의 낙관적 잠금은 {@link #claimRevision}의 조건부 UPDATE다.
 */
@Repository
public class ReleaseNoteQueryRepository {

    private static final OrderSpecifier<?>[] NEWEST_FIRST = {releaseNote.versionMajor.desc(),
            releaseNote.versionMinor.desc(), releaseNote.versionPatch.desc()};

    private final JPAQueryFactory queryFactory;

    public ReleaseNoteQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 게시된 노트 전체, 버전 내림차순. */
    public List<ReleaseNote> findPublished() {
        return queryFactory.selectFrom(releaseNote)
                .where(releaseNote.status.eq(ReleaseNoteStatus.PUBLISHED))
                .orderBy(NEWEST_FIRST)
                .fetch();
    }

    /** 가장 높은 버전의 게시 노트, 없으면 null. */
    public ReleaseNote findLatestPublished() {
        return queryFactory.selectFrom(releaseNote)
                .where(releaseNote.status.eq(ReleaseNoteStatus.PUBLISHED))
                .orderBy(NEWEST_FIRST)
                .limit(1)
                .fetchFirst();
    }

    /** 그 버전의 게시 노트, 없거나 초안이면 null. */
    public ReleaseNote findPublished(String version) {
        return queryFactory.selectFrom(releaseNote)
                .where(releaseNote.version.eq(version), releaseNote.status.eq(ReleaseNoteStatus.PUBLISHED))
                .fetchOne();
    }

    /** 게시 노트 중 바로 앞(낮은) 버전, 없으면 null. */
    public ReleaseNote findPrevious(SemVer version) {
        return queryFactory.selectFrom(releaseNote)
                .where(releaseNote.status.eq(ReleaseNoteStatus.PUBLISHED), lowerThan(version))
                .orderBy(NEWEST_FIRST)
                .limit(1)
                .fetchFirst();
    }

    /** 게시 노트 중 바로 뒤(높은) 버전, 없으면 null. */
    public ReleaseNote findNext(SemVer version) {
        return queryFactory.selectFrom(releaseNote)
                .where(releaseNote.status.eq(ReleaseNoteStatus.PUBLISHED), higherThan(version))
                .orderBy(releaseNote.versionMajor.asc(), releaseNote.versionMinor.asc(), releaseNote.versionPatch.asc())
                .limit(1)
                .fetchFirst();
    }

    /** 노트 id들의 언어판 제목, 노트 id → (언어 → 제목). 쿼리 1회. */
    public Map<Long, Map<String, String>> findTitles(Collection<Long> noteIds) {
        Map<Long, Map<String, String>> titles = new LinkedHashMap<>();
        if (noteIds.isEmpty()) {
            return titles;
        }
        List<ContentTitleRow> rows = queryFactory
                .select(Projections.constructor(ContentTitleRow.class, releaseNoteContent.releaseNote.id,
                        releaseNoteContent.id.lang, releaseNoteContent.title))
                .from(releaseNoteContent)
                .where(releaseNoteContent.releaseNote.id.in(noteIds))
                .fetch();
        for (ContentTitleRow row : rows) {
            titles.computeIfAbsent(row.noteId(), id -> new LinkedHashMap<>()).put(row.lang(), row.title());
        }
        return titles;
    }

    /** 노트 id들의 언어판 제목과 본문 텍스트(검색 일치 부분용). 노트 id → (언어 → [제목, 텍스트]). 쿼리 1회. */
    public Map<Long, Map<String, String[]>> findTexts(Collection<Long> noteIds) {
        Map<Long, Map<String, String[]>> texts = new LinkedHashMap<>();
        if (noteIds.isEmpty()) {
            return texts;
        }
        queryFactory.select(releaseNoteContent.releaseNote.id, releaseNoteContent.id.lang, releaseNoteContent.title,
                        releaseNoteContent.contentText)
                .from(releaseNoteContent)
                .where(releaseNoteContent.releaseNote.id.in(noteIds))
                .fetch()
                .forEach(t -> texts.computeIfAbsent(t.get(0, Long.class), id -> new LinkedHashMap<>())
                        .put(t.get(1, String.class), new String[] {t.get(2, String.class), t.get(3, String.class)}));
        return texts;
    }

    /** 수정본 목록(새 것 먼저). {@code fromRevisionNo}가 있으면 그 번호부터(독자 "수정 이력"). 쿼리 1회. */
    public List<RevisionRow> findRevisions(long noteId, Integer fromRevisionNo) {
        BooleanExpression from = fromRevisionNo == null ? null : releaseNoteRevision.revisionNo.goe(fromRevisionNo);
        return queryFactory
                .select(Projections.constructor(RevisionRow.class, releaseNoteRevision.revisionNo,
                        releaseNoteRevision.createdAt, releaseNoteRevision.status, user.id, user.nickname))
                .from(releaseNoteRevision)
                .join(releaseNoteRevision.editedBy, user)
                .where(releaseNoteRevision.releaseNote.id.eq(noteId), from)
                .orderBy(releaseNoteRevision.revisionNo.desc())
                .fetch();
    }

    /** 처음 게시 수정본부터의 수정본 수(독자 상세의 {@code revisionCount}). */
    public long countRevisionsSince(long noteId, int fromRevisionNo) {
        Long count = queryFactory.select(releaseNoteRevision.count())
                .from(releaseNoteRevision)
                .where(releaseNoteRevision.releaseNote.id.eq(noteId), releaseNoteRevision.revisionNo.goe(fromRevisionNo))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 관리 목록: 상태(null이면 전체), 버전 내림차순. 쿼리 2회(목록, 수). */
    public Page<ReleaseNote> findForAdmin(ReleaseNoteStatus status, Pageable pageable) {
        BooleanExpression where = status == null ? null : releaseNote.status.eq(status);
        List<ReleaseNote> rows = queryFactory.selectFrom(releaseNote)
                .where(where)
                .orderBy(NEWEST_FIRST)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(releaseNote.count()).from(releaseNote).where(where).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /**
     * 저장의 낙관적 잠금(006 FR-168): {@code current_revision_no}가 {@code baseRevisionNo}일 때만 1 올린다. 바뀐 행 수(0이면 다른 관리자가
     * 먼저 저장했거나 노트가 없다). 이 노트를 영속성 컨텍스트에 올리기 전에 불러야 한다.
     */
    public long claimRevision(long noteId, int baseRevisionNo, Instant now) {
        return queryFactory.update(releaseNote)
                .set(releaseNote.currentRevisionNo, baseRevisionNo + 1)
                .set(releaseNote.updatedAt, now)
                .where(releaseNote.id.eq(noteId), releaseNote.currentRevisionNo.eq(baseRevisionNo))
                .execute();
    }

    private static BooleanExpression lowerThan(SemVer v) {
        return releaseNote.versionMajor.lt(v.major())
                .or(releaseNote.versionMajor.eq(v.major()).and(releaseNote.versionMinor.lt(v.minor())))
                .or(releaseNote.versionMajor.eq(v.major()).and(releaseNote.versionMinor.eq(v.minor()))
                        .and(releaseNote.versionPatch.lt(v.patch())));
    }

    private static BooleanExpression higherThan(SemVer v) {
        return releaseNote.versionMajor.gt(v.major())
                .or(releaseNote.versionMajor.eq(v.major()).and(releaseNote.versionMinor.gt(v.minor())))
                .or(releaseNote.versionMajor.eq(v.major()).and(releaseNote.versionMinor.eq(v.minor()))
                        .and(releaseNote.versionPatch.gt(v.patch())));
    }
}
