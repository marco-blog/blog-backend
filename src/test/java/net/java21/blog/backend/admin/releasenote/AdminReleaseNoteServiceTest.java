package net.java21.blog.backend.admin.releasenote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.audit.AdminAuditLog;
import net.java21.blog.backend.admin.audit.AdminAuditService;
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
import net.java21.blog.backend.content.HtmlSanitizerPolicy;
import net.java21.blog.backend.content.VideoEmbedTransformer;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContent;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContentId;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteQueryRepository;
import net.java21.blog.backend.releasenote.service.ReleaseNoteRenderer;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.user.domain.User;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * 릴리스 노트 관리(003 T107, 006 FR-167·168). 조건부 UPDATE와 언어판·수정본 저장을 실제 JPA(H2)로 확인한다: 만들기(DRAFT, 수정본 1, 형식
 * 검사, 중복 409), 수정(기준 수정본 충돌 409, 새 수정본, 빠진 언어판 삭제, 게시 이력 뒤 버전 변경 422), 게시·게시 중단, 삭제, 미리보기,
 * 수정본 목록·내용, 작업 기록과 {@link PortalChangedEvent}.
 */
@JpaRepositoryTest
@RecordApplicationEvents
@Import({AdminReleaseNoteService.class, ReleaseNoteQueryRepository.class, ReleaseNoteRenderer.class,
        HtmlSanitizerPolicy.class, VideoEmbedTransformer.class, AdminAuditService.class,
        AdminReleaseNoteServiceTest.ClockConfig.class})
class AdminReleaseNoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final String IP = "::1";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private EntityManager em;
    @Autowired
    private AdminReleaseNoteService service;
    @Autowired
    private ApplicationEvents events;

    private JpaFixtures fx;
    private long admin;
    private long other;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        User a = fx.user("관리자");
        User b = fx.user("다른관리자");
        fx.flushAndClear();
        admin = a.getId();
        other = b.getId();
    }

    @Test
    void createMakesDraftRevisionOneWithRenderedContents() {
        AdminReleaseNoteResponse created = service.create(admin, write("1.2.0", Map.of(
                "en", new ContentWrite("New", "## Features\n\nbody"),
                "ko", new ContentWrite(" 새 기능 ", "## 새 기능\n\n본문"))), IP);
        fx.flushAndClear();

        assertThat(created.status()).isEqualTo(ReleaseNoteStatus.DRAFT);
        assertThat(created.revisionNo()).isEqualTo(1);
        assertThat(created.contents().keySet()).containsExactly("ko", "en");
        assertThat(created.contents().get("ko").title()).isEqualTo("새 기능");
        assertThat(created.createdBy().nickname()).isEqualTo("관리자");
        assertThat(created.firstPublishedAt()).isNull();
        ReleaseNoteContent ko = em.find(ReleaseNoteContent.class, new ReleaseNoteContentId(created.id(), "ko"));
        assertThat(ko.getContentHtml()).contains("<h2 id=\"새-기능\">새 기능</h2>");
        assertThat(ko.getContentText()).isEqualTo("새 기능 본문");
        assertThat(ko.getToc()).hasSize(1);
        assertThat(service.revisions(created.id())).extracting(AdminRevisionResponse::revisionNo).containsExactly(1);
        AdminAuditLog log = audit().get(0);
        assertThat(log.getAction()).isEqualTo("RELEASE_NOTE_CREATE");
        assertThat(log.getTargetId()).isEqualTo(created.id());
        assertThat(log.getTargetKey()).isEqualTo("1.2.0");
        assertThat(log.getAfter()).containsEntry("langs", List.of("ko", "en"));
        assertThat(events.stream(PortalChangedEvent.class)).hasSize(1);

        expect(() -> service.create(admin, write("1.2.0", Map.of("ko", new ContentWrite("t", "b"))), IP),
                ErrorCode.RELEASE_NOTE_VERSION_TAKEN);
    }

    @Test
    void writeValidationCollectsAllErrors() {
        assertFields(new ReleaseNoteWriteRequest(null, null, null), "version:REQUIRED", "releaseDate:REQUIRED",
                "contents.ko:REQUIRED");
        assertFields(write("01.2.0", Map.of("en", new ContentWrite("t", "b"))), "version:INVALID_FORMAT",
                "contents.ko:REQUIRED");
        Map<String, ContentWrite> contents = new LinkedHashMap<>();
        contents.put("ko", new ContentWrite("", "가".repeat(100_001)));
        contents.put("fr", new ContentWrite("t", "b"));
        contents.put("ja", new ContentWrite("가".repeat(201), null));
        assertFields(write("1.0.0", contents), "contents.ko.title:REQUIRED", "contents.ko.contentMarkdown:TOO_LONG",
                "contents.fr:INVALID", "contents.ja.title:TOO_LONG", "contents.ja.contentMarkdown:REQUIRED");
        assertFields(new UpdateReleaseNoteRequest("1.0.0", LocalDate.of(2026, 10, 6),
                Map.of("ko", new ContentWrite("t", "b")), null), "baseRevisionNo:REQUIRED");
    }

    @Test
    void updateUsesBaseRevisionReplacesContentsAndKeepsHistory() {
        long id = service.create(admin, write("1.2.0", Map.of("ko", new ContentWrite("하나", "본문"),
                "en", new ContentWrite("One", "body"))), IP).id();
        fx.flushAndClear();

        AdminReleaseNoteResponse updated = service.update(other, id, update("1.2.1", 1,
                Map.of("ko", new ContentWrite("둘", "## 바뀐 본문"))), IP);
        fx.flushAndClear();

        assertThat(updated.revisionNo()).isEqualTo(2);
        assertThat(updated.version()).isEqualTo("1.2.1");
        assertThat(updated.contents()).containsOnlyKeys("ko");
        assertThat(updated.updatedBy().nickname()).isEqualTo("다른관리자");
        assertThat(em.find(ReleaseNoteContent.class, new ReleaseNoteContentId(id, "en"))).isNull();
        assertThat(service.revisions(id)).extracting(AdminRevisionResponse::revisionNo).containsExactly(2, 1);
        AdminRevisionResponse first = service.revision(id, 1);
        assertThat(first.contents()).containsOnlyKeys("ko", "en");
        assertThat(first.contents().get("en").title()).isEqualTo("One");
        assertThat(first.editedBy().nickname()).isEqualTo("관리자");
        assertThat(first.version()).isEqualTo("1.2.0");
        expect(() -> service.revision(id, 9), ErrorCode.RELEASE_NOTE_NOT_FOUND);

        // 같은 기준 번호로 다시 저장하면 충돌
        expect(() -> service.update(admin, id, update("1.2.1", 1, Map.of("ko", new ContentWrite("셋", "b"))), IP),
                ErrorCode.RELEASE_NOTE_REVISION_CONFLICT);
        expect(() -> service.update(admin, 999L, update("1.2.1", 1, Map.of("ko", new ContentWrite("셋", "b"))), IP),
                ErrorCode.RELEASE_NOTE_NOT_FOUND);
        AdminAuditLog log = audit().get(1);
        assertThat(log.getAction()).isEqualTo("RELEASE_NOTE_UPDATE");
        assertThat(log.getBefore()).containsEntry("version", "1.2.0").containsEntry("revisionNo", 1);
        assertThat(log.getAfter()).containsEntry("version", "1.2.1").containsEntry("revisionNo", 2);
    }

    @Test
    void publishKeepsFirstPublishAndLocksVersion() {
        long id = service.create(admin, write("1.2.0", Map.of("ko", new ContentWrite("하나", "본문"))), IP).id();
        service.create(admin, write("1.3.0", Map.of("ko", new ContentWrite("다른", "본문"))), IP);
        fx.flushAndClear();

        AdminReleaseNoteResponse published = service.publish(admin, id, IP);
        assertThat(published.status()).isEqualTo(ReleaseNoteStatus.PUBLISHED);
        assertThat(published.firstPublishedAt()).isEqualTo(NOW);
        assertThat(published.publishedAt()).isEqualTo(NOW);
        assertThat(service.publish(admin, id, IP).publishedAt()).isEqualTo(NOW);
        fx.flushAndClear();

        AdminReleaseNoteResponse unpublished = service.unpublish(admin, id, IP);
        assertThat(unpublished.status()).isEqualTo(ReleaseNoteStatus.DRAFT);
        assertThat(unpublished.publishedAt()).isNull();
        assertThat(unpublished.firstPublishedAt()).isEqualTo(NOW);
        assertThat(service.unpublish(admin, id, IP).status()).isEqualTo(ReleaseNoteStatus.DRAFT);
        fx.flushAndClear();

        expect(() -> service.update(admin, id, update("1.2.5", 1, Map.of("ko", new ContentWrite("t", "b"))), IP),
                ErrorCode.RELEASE_NOTE_VERSION_LOCKED);
        fx.flushAndClear();
        expect(() -> service.delete(admin, id, IP), ErrorCode.RELEASE_NOTE_ONCE_PUBLISHED);
        fx.flushAndClear();
        // 같은 버전이면 게시 뒤에도 고칠 수 있다. 위의 실패한 저장이 올린 번호는 운영에서는 트랜잭션과 함께 롤백되지만, 이 테스트는
        // 한 트랜잭션이라 남아 있다(지금 번호 2).
        assertThat(service.update(admin, id, update("1.2.0", 2, Map.of("ko", new ContentWrite("고침", "b"))), IP)
                .revisionNo()).isEqualTo(3);
        assertThat(audit()).extracting(AdminAuditLog::getAction).containsExactly("RELEASE_NOTE_CREATE",
                "RELEASE_NOTE_CREATE", "RELEASE_NOTE_PUBLISH", "RELEASE_NOTE_UNPUBLISH", "RELEASE_NOTE_UPDATE");
        assertThat(events.stream(PortalChangedEvent.class)).hasSize(5);
    }

    @Test
    void draftVersionCanChangeUnlessTaken() {
        long id = service.create(admin, write("1.2.0", Map.of("ko", new ContentWrite("하나", "본문"))), IP).id();
        service.create(admin, write("1.3.0", Map.of("ko", new ContentWrite("다른", "본문"))), IP);
        fx.flushAndClear();

        expect(() -> service.update(admin, id, update("1.3.0", 1, Map.of("ko", new ContentWrite("t", "b"))), IP),
                ErrorCode.RELEASE_NOTE_VERSION_TAKEN);
    }

    @Test
    void deleteRemovesNeverPublishedDraftsWithRevisions() {
        long id = service.create(admin, write("1.2.0", Map.of("ko", new ContentWrite("하나", "본문"))), IP).id();
        fx.flushAndClear();

        service.delete(admin, id, IP);
        fx.flushAndClear();

        expect(() -> service.get(id), ErrorCode.RELEASE_NOTE_NOT_FOUND);
        expect(() -> service.revisions(id), ErrorCode.RELEASE_NOTE_NOT_FOUND);
        assertThat(em.createQuery("select count(r) from ReleaseNoteRevision r", Long.class).getSingleResult())
                .isZero();
        assertThat(audit().get(1).getAction()).isEqualTo("RELEASE_NOTE_DELETE");
        assertThat(audit().get(1).getBefore()).containsEntry("version", "1.2.0");
        expect(() -> service.delete(admin, id, IP), ErrorCode.RELEASE_NOTE_NOT_FOUND);
    }

    @Test
    void listFiltersByStatusWithLangs() {
        long a = service.create(admin, write("1.2.0", Map.of("en", new ContentWrite("e", "b"),
                "ko", new ContentWrite("하나", "본문"))), IP).id();
        service.create(admin, write("1.10.0", Map.of("ko", new ContentWrite("둘", "본문"))), IP);
        service.publish(admin, a, IP);
        fx.flushAndClear();

        List<AdminReleaseNoteSummary> all = service.list(null, PageRequest.of(0, 20)).getContent();
        assertThat(all).extracting(AdminReleaseNoteSummary::version).containsExactly("1.10.0", "1.2.0");
        assertThat(all.get(1).langs()).containsExactly("ko", "en");
        assertThat(service.list("PUBLISHED", PageRequest.of(0, 20)).getContent())
                .extracting(AdminReleaseNoteSummary::id).containsExactly(a);
        assertThatThrownBy(() -> service.list("OPEN", PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.fieldErrors().get(0).params()).containsKey("allowed"));
    }

    @Test
    void previewRendersWithoutSaving() {
        PreviewResponse preview = service.preview("## 미리 보기\n\n<script>x</script>");

        assertThat(preview.contentHtml()).contains("<h2 id=\"미리-보기\">").doesNotContain("script");
        assertThat(preview.toc()).hasSize(1);
        assertThat(service.list(null, PageRequest.of(0, 20)).getTotalElements()).isZero();
    }

    private List<AdminAuditLog> audit() {
        return em.createQuery("select l from AdminAuditLog l order by l.id", AdminAuditLog.class).getResultList();
    }

    private static ReleaseNoteWriteRequest write(String version, Map<String, ContentWrite> contents) {
        return new ReleaseNoteWriteRequest(version, LocalDate.of(2026, 10, 6), contents);
    }

    private static UpdateReleaseNoteRequest update(String version, int base, Map<String, ContentWrite> contents) {
        return new UpdateReleaseNoteRequest(version, LocalDate.of(2026, 10, 7), contents, base);
    }

    private void assertFields(ReleaseNoteWriteRequest request, String... expected) {
        assertThatThrownBy(() -> service.create(admin, request, IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors())
                        .extracting(f -> f.field() + ":" + f.code()).containsExactlyInAnyOrder(expected));
    }

    private void assertFields(UpdateReleaseNoteRequest request, String... expected) {
        assertThatThrownBy(() -> service.update(admin, 1L, request, IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors())
                        .extracting(FieldError::field).containsExactly(
                                java.util.Arrays.stream(expected).map(s -> s.split(":")[0]).toArray(String[]::new)));
    }

    private static void expect(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
