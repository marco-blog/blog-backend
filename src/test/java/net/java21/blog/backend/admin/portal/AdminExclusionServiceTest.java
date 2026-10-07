package net.java21.blog.backend.admin.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.portal.dto.AdminPortalPostResponse;
import net.java21.blog.backend.admin.portal.dto.ExclusionResponse;
import net.java21.blog.backend.admin.portal.repository.CurationQueryRepository;
import net.java21.blog.backend.admin.portal.repository.ExclusionRow;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** 포털 제외(003 T085, FR-093): 사유 검증, 멱등 PUT(생성·사유 변경), 해제 404, 글 찾기의 노출 이유, 작업 기록과 캐시 비우기. */
@ExtendWith(MockitoExtension.class)
class AdminExclusionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final long ADMIN = 9L;
    private static final String IP = "::1";

    @Mock
    private PortalExclusionRepository exclusionRepository;
    @Mock
    private CurationQueryRepository queryRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private PortalCriteriaFactory criteriaFactory;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAuditService auditService;
    @Mock
    private ApplicationEventPublisher events;

    private AdminExclusionService service;
    private User admin;
    private Post post;

    @BeforeEach
    void setUp() {
        service = new AdminExclusionService(exclusionRepository, queryRepository, postRepository, criteriaFactory,
                userRepository, auditService, events);
        admin = TestEntities.user(ADMIN);
        User owner = TestEntities.user(1L);
        TestEntities.with(owner, "createdAt", NOW.minus(Duration.ofDays(3)));
        Blog blog = TestEntities.blog(10L, owner, "marco");
        post = TestEntities.post(100L, blog, "글");
        String text = "가".repeat(250);
        post.publish("글", text, "<p>" + text + "</p>", text, "요약", null, PostVisibility.PUBLIC, true,
                NOW.minusSeconds(60));
        lenient().when(userRepository.getReferenceById(ADMIN)).thenReturn(admin);
        lenient().when(criteriaFactory.now()).thenReturn(new PortalCriteria(NOW, Duration.ofHours(24), 200));
    }

    @Test
    void listMapsRows() {
        when(queryRepository.findExclusions(PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(row("광고")), PageRequest.of(0, 20), 1));

        List<ExclusionResponse> content = service.list(PageRequest.of(0, 20)).getContent();

        assertThat(content).hasSize(1);
        assertThat(content.get(0).post().id()).isEqualTo(100L);
        assertThat(content.get(0).post().blogHandle()).isEqualTo("marco");
        assertThat(content.get(0).excludedBy().nickname()).isEqualTo("관리자");
    }

    @Test
    void excludeCreatesRowAndRecordsReason() {
        when(postRepository.findById(100L)).thenReturn(Optional.of(post));
        when(exclusionRepository.findByPostId(100L)).thenReturn(Optional.empty());
        when(queryRepository.findExclusion(100L)).thenReturn(row("광고"));

        ExclusionResponse response = service.exclude(ADMIN, 100L, "  광고  ", IP);

        assertThat(response.reason()).isEqualTo("광고");
        ArgumentCaptor<PortalExclusion> saved = ArgumentCaptor.forClass(PortalExclusion.class);
        verify(exclusionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("광고");
        assertThat(saved.getValue().getExcludedBy()).isSameAs(admin);
        Map<String, Object> before = new java.util.HashMap<>();
        before.put("reason", null);
        verify(auditService).record(ADMIN, AuditActions.PORTAL_EXCLUDE, AuditActions.TARGET_POST, 100L, null,
                before, Map.of("reason", "광고"), "광고", IP);
        verify(events).publishEvent(any(PortalChangedEvent.class));
    }

    @Test
    void excludeAgainChangesReasonOnly() {
        PortalExclusion existing = new PortalExclusion(post, "광고", TestEntities.user(8L));
        when(postRepository.findById(100L)).thenReturn(Optional.of(post));
        when(exclusionRepository.findByPostId(100L)).thenReturn(Optional.of(existing));
        when(queryRepository.findExclusion(100L)).thenReturn(row("도배"));

        service.exclude(ADMIN, 100L, "도배", IP);

        assertThat(existing.getReason()).isEqualTo("도배");
        assertThat(existing.getExcludedBy()).isSameAs(admin);
        verify(exclusionRepository, never()).saveAndFlush(any());
        verify(auditService).record(ADMIN, AuditActions.PORTAL_EXCLUDE, AuditActions.TARGET_POST, 100L, null,
                Map.of("reason", "광고"), Map.of("reason", "도배"), "도배", IP);
    }

    @Test
    void excludeValidatesReasonAndPost() {
        assertField(() -> service.exclude(ADMIN, 100L, null, IP), "REQUIRED");
        assertField(() -> service.exclude(ADMIN, 100L, "   ", IP), "REQUIRED");
        assertThatThrownBy(() -> service.exclude(ADMIN, 100L, "가".repeat(501), IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors())
                        .containsExactly(new FieldError("reason", "TOO_LONG", Map.of("max", 500))));
        when(postRepository.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.exclude(ADMIN, 5L, "광고", IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void unexcludeDeletesOrSays404() {
        PortalExclusion existing = new PortalExclusion(post, "광고", admin);
        when(exclusionRepository.findByPostId(100L)).thenReturn(Optional.of(existing));

        service.unexclude(ADMIN, 100L, IP);

        verify(exclusionRepository).delete(existing);
        verify(auditService).record(eq(ADMIN), eq(AuditActions.PORTAL_UNEXCLUDE), eq(AuditActions.TARGET_POST),
                eq(100L), eq(Map.of("reason", "광고")), any(), eq(IP));
        verify(events).publishEvent(any(PortalChangedEvent.class));

        when(exclusionRepository.findByPostId(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.unexclude(ADMIN, 5L, IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PORTAL_EXCLUSION_NOT_FOUND));
    }

    @Test
    void lookupShowsEligibilityAndExclusion() {
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(queryRepository.findExclusion(100L)).thenReturn(null, row("광고"));

        AdminPortalPostResponse visible = service.lookup(100L);
        assertThat(visible.portalEligible()).isTrue();
        assertThat(visible.ineligibleReasons()).isEmpty();
        assertThat(visible.excluded()).isNull();
        assertThat(visible.blog().handle()).isEqualTo("marco");

        AdminPortalPostResponse excluded = service.lookup(100L);
        assertThat(excluded.portalEligible()).isFalse();
        assertThat(excluded.ineligibleReasons()).containsExactly("EXCLUDED");
        assertThat(excluded.excluded().reason()).isEqualTo("광고");
        assertThat(excluded.excluded().excludedBy().userId()).isEqualTo(ADMIN);

        when(postRepository.findWithBlogAndOwner(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.lookup(5L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
    }

    private static ExclusionRow row(String reason) {
        return new ExclusionRow(100L, "글", "marco", reason, ADMIN, "관리자", NOW, NOW);
    }

    private static void assertField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fieldErrors()).extracting(FieldError::code).containsExactly(code);
        });
    }
}
