package net.java21.blog.backend.admin.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.portal.dto.CreateCurationRequest;
import net.java21.blog.backend.admin.portal.dto.CurationResponse;
import net.java21.blog.backend.admin.portal.dto.UpdateCurationRequest;
import net.java21.blog.backend.admin.portal.repository.CurationQueryRepository;
import net.java21.blog.backend.admin.portal.repository.CurationRow;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.domain.PortalCuration;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.repository.PortalCurationRepository;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.portal.service.PortalCriteriaFactory;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** 운영자 추천(003 T084, FR-091·092): 노출 조건·기간·5편 한도 검증, 상태·노출 여부 응답, 작업 기록과 캐시 비우기. */
@ExtendWith(MockitoExtension.class)
class AdminCurationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final long ADMIN = 9L;
    private static final String IP = "::1";
    private static final String TEXT = "가".repeat(250);

    @Mock
    private PortalCurationRepository curationRepository;
    @Mock
    private CurationQueryRepository queryRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private PortalExclusionRepository exclusionRepository;
    @Mock
    private PortalCriteriaFactory criteriaFactory;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAuditService auditService;
    @Mock
    private ApplicationEventPublisher events;

    private AdminCurationService service;
    private User admin;
    private Post post;

    @BeforeEach
    void setUp() {
        service = new AdminCurationService(curationRepository, queryRepository, postRepository, exclusionRepository,
                criteriaFactory, userRepository, auditService, events, Clock.fixed(NOW, ZoneOffset.UTC));
        admin = TestEntities.user(ADMIN);
        User owner = TestEntities.user(1L);
        TestEntities.with(owner, "createdAt", NOW.minus(Duration.ofDays(3)));
        Blog blog = TestEntities.blog(10L, owner, "marco");
        post = TestEntities.post(100L, blog, "글");
        post.publish("글", TEXT, "<p>" + TEXT + "</p>", TEXT, "요약", null, PostVisibility.PUBLIC, true,
                NOW.minusSeconds(60));
        lenient().when(criteriaFactory.now()).thenReturn(new PortalCriteria(NOW, Duration.ofHours(24), 200));
        lenient().when(userRepository.getReferenceById(ADMIN)).thenReturn(admin);
        lenient().when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
    }

    @Test
    void createSavesRecordsAndAnswersWithStatusAndEligibility() {
        when(queryRepository.countOverlapping(NOW, NOW.plusSeconds(3600), null)).thenReturn(4L);
        when(curationRepository.saveAndFlush(any(PortalCuration.class))).thenAnswer(inv -> {
            PortalCuration saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 7L);
            return saved;
        });
        stubRow(7L, NOW, NOW.plusSeconds(3600), 0);
        when(queryRepository.findPortalVisiblePostIds(Set.of(100L), criteriaFactory.now())).thenReturn(Set.of(100L));

        CurationResponse response = service.create(ADMIN, new CreateCurationRequest(100L, NOW, NOW.plusSeconds(3600),
                null), IP);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(CurationStatus.ACTIVE);
        assertThat(response.portalEligible()).isTrue();
        assertThat(response.post().blogHandle()).isEqualTo("marco");
        assertThat(response.createdBy().nickname()).isEqualTo("관리자");
        ArgumentCaptor<PortalCuration> saved = ArgumentCaptor.forClass(PortalCuration.class);
        verify(curationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSortOrder()).isZero();
        assertThat(saved.getValue().getCreatedBy()).isSameAs(admin);
        verify(auditService).record(eq(ADMIN), eq("CURATION_CREATE"), eq("CURATION"), eq(7L), eq(null), any(),
                eq(IP));
        verify(events).publishEvent(any(PortalChangedEvent.class));
    }

    @Test
    void createRejectsIneligibleMissingPostsBadPeriodsAndFullSlots() {
        Post privatePost = TestEntities.post(101L, post.getBlog(), "비공개");
        privatePost.publish("비공개", "짧다", "<p>짧다</p>", "짧다", "짧다", null, PostVisibility.PRIVATE, true, NOW);
        when(postRepository.findWithBlogAndOwner(101L)).thenReturn(Optional.of(privatePost));
        when(postRepository.findWithBlogAndOwner(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(ADMIN, request(101L), IP)).isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.POST_NOT_PORTAL_ELIGIBLE);
                    assertThat(be.getMessage()).contains("NOT_BODY_VISIBLE", "TOO_SHORT");
                });
        assertCode(() -> service.create(ADMIN, request(404L), IP), ErrorCode.POST_NOT_FOUND);
        when(exclusionRepository.existsByPostId(100L)).thenReturn(true);
        assertThatThrownBy(() -> service.create(ADMIN, request(100L), IP))
                .hasMessageContaining("EXCLUDED");
        when(exclusionRepository.existsByPostId(100L)).thenReturn(false);
        when(queryRepository.countOverlapping(any(), any(), eq(null))).thenReturn(5L);
        assertCode(() -> service.create(ADMIN, request(100L), IP), ErrorCode.CURATION_LIMIT_EXCEEDED);

        assertField(() -> service.create(ADMIN, new CreateCurationRequest(100L, NOW, NOW, 0), IP), "endsAt", "INVALID");
        assertField(() -> service.create(ADMIN, new CreateCurationRequest(100L, NOW, NOW.minusSeconds(1), 0), IP),
                "endsAt", "INVALID");
        assertField(() -> service.create(ADMIN, new CreateCurationRequest(null, NOW, NOW.plusSeconds(1), 0), IP),
                "postId", "REQUIRED");
        assertField(() -> service.create(ADMIN, new CreateCurationRequest(100L, null, NOW, 0), IP), "startsAt",
                "REQUIRED");
        assertField(() -> service.create(ADMIN, new CreateCurationRequest(100L, NOW, null, 0), IP), "endsAt",
                "REQUIRED");
        verify(curationRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateChangesPeriodExcludingItselfFromTheCount() {
        PortalCuration curation = new PortalCuration(post, NOW, NOW.plusSeconds(60), 2, admin);
        ReflectionTestUtils.setField(curation, "id", 7L);
        when(curationRepository.findById(7L)).thenReturn(Optional.of(curation));
        when(queryRepository.countOverlapping(NOW.minusSeconds(120), NOW.minusSeconds(60), 7L)).thenReturn(4L);
        stubRow(7L, NOW.minusSeconds(120), NOW.minusSeconds(60), 2);
        when(queryRepository.findPortalVisiblePostIds(Set.of(100L), criteriaFactory.now())).thenReturn(Set.of());

        CurationResponse response = service.update(ADMIN, 7L,
                new UpdateCurationRequest(NOW.minusSeconds(120), NOW.minusSeconds(60), null), IP);

        assertThat(curation.getStartsAt()).isEqualTo(NOW.minusSeconds(120));
        assertThat(curation.getSortOrder()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(CurationStatus.ENDED);
        assertThat(response.portalEligible()).isFalse();
        verify(auditService).record(eq(ADMIN), eq("CURATION_UPDATE"), eq("CURATION"), eq(7L), any(), any(), eq(IP));

        when(queryRepository.countOverlapping(NOW, NOW.plusSeconds(60), 7L)).thenReturn(5L);
        assertCode(() -> service.update(ADMIN, 7L, new UpdateCurationRequest(NOW, NOW.plusSeconds(60), 1), IP),
                ErrorCode.CURATION_LIMIT_EXCEEDED);
        assertField(() -> service.update(ADMIN, 7L, new UpdateCurationRequest(null, NOW.minusSeconds(500), null), IP),
                "endsAt", "INVALID");
        when(curationRepository.findById(8L)).thenReturn(Optional.empty());
        assertCode(() -> service.update(ADMIN, 8L, new UpdateCurationRequest(null, null, 1), IP),
                ErrorCode.CURATION_NOT_FOUND);
    }

    @Test
    void deleteRemovesAndRecords() {
        PortalCuration curation = new PortalCuration(post, NOW, NOW.plusSeconds(60), 0, admin);
        when(curationRepository.findById(7L)).thenReturn(Optional.of(curation));
        when(curationRepository.findById(8L)).thenReturn(Optional.empty());

        service.delete(ADMIN, 7L, IP);

        verify(curationRepository).delete(curation);
        verify(auditService).record(eq(ADMIN), eq("CURATION_DELETE"), eq("CURATION"), eq(7L), any(), eq(null), eq(IP));
        verify(events).publishEvent(any(PortalChangedEvent.class));
        assertCode(() -> service.delete(ADMIN, 8L, IP), ErrorCode.CURATION_NOT_FOUND);
    }

    @Test
    void listParsesStatusAndMarksEligibilityInOneLookup() {
        CurationRow active = row(7L, 100L, NOW.minusSeconds(10), NOW.plusSeconds(10));
        CurationRow lost = row(8L, 101L, NOW.minusSeconds(10), NOW.plusSeconds(10));
        Page<CurationRow> page = new PageImpl<>(List.of(active, lost), PageRequest.of(0, 20), 2);
        when(queryRepository.findCurations(CurationStatus.ACTIVE, NOW, PageRequest.of(0, 20))).thenReturn(page);
        when(queryRepository.findPortalVisiblePostIds(Set.of(100L, 101L), criteriaFactory.now()))
                .thenReturn(Set.of(100L));

        Page<CurationResponse> result = service.list("ACTIVE", PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(CurationResponse::portalEligible).containsExactly(true, false);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(AdminCurationService.parseStatus(null)).isNull();
        assertThatThrownBy(() -> service.list("NOW", PageRequest.of(0, 20))).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).fieldErrors()).containsExactly(new FieldError(
                        "status", "INVALID", Map.of("allowed", List.of("ACTIVE", "UPCOMING", "ENDED")))));
        verify(auditService, never()).record(anyLong(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void statusFollowsTheHalfOpenPeriod() {
        assertThat(CurationStatus.of(NOW, NOW.plusSeconds(1), NOW)).isEqualTo(CurationStatus.ACTIVE);
        assertThat(CurationStatus.of(NOW.plusSeconds(1), NOW.plusSeconds(2), NOW)).isEqualTo(CurationStatus.UPCOMING);
        assertThat(CurationStatus.of(NOW.minusSeconds(1), NOW, NOW)).isEqualTo(CurationStatus.ENDED);
    }

    private void stubRow(long id, Instant starts, Instant ends, int sortOrder) {
        when(queryRepository.findCuration(id)).thenReturn(new CurationRow(id, 100L, "글", "marco", starts, ends,
                sortOrder, ADMIN, "관리자", NOW, NOW));
    }

    private static CurationRow row(long id, long postId, Instant starts, Instant ends) {
        return new CurationRow(id, postId, "글", "marco", starts, ends, 0, ADMIN, "관리자", NOW, NOW);
    }

    private static CreateCurationRequest request(long postId) {
        return new CreateCurationRequest(postId, NOW, NOW.plusSeconds(3600), 1);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(code);
    }

    private static void assertField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field,
            String code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).fieldErrors()).containsExactly(
                        FieldError.of(field, code)));
    }
}
