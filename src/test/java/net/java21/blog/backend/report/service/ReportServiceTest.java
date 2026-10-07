package net.java21.blog.backend.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.CreateReportRequest;
import net.java21.blog.backend.report.dto.ReportCreatedResponse;
import net.java21.blog.backend.report.repository.ReportRepository;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 005 T030: 회원 신고 접수. 종류·사유·설명 검증(OTHER면 설명 필수, 1000자), 비활성 회원 401, 1시간 한도(429), 처리기 404·422, 같은 대상
 * 409(조회와 UNIQUE 위반 모두), 대상 작성 회원·블로그 저장.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final long REPORTER = 7L;

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private ReportTargetHandler postHandler;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RateLimitPolicy rateLimits;

    private ReportService service;
    private User reporter;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        lenient().when(postHandler.type()).thenReturn(ReportTargetType.POST);
        service = new ReportService(reportRepository, new ReportTargetHandlers(List.of(postHandler)), userRepository,
                rateLimits);
        reporter = TestEntities.user(REPORTER);
        owner = TestEntities.user(1L);
        blog = TestEntities.blog(10L, owner, "owner");
        lenient().when(userRepository.findById(REPORTER)).thenReturn(Optional.of(reporter));
    }

    @Test
    void savesTheReportWithTheTargetAuthorAndBlog() {
        when(postHandler.resolveForReporter(5L, REPORTER))
                .thenReturn(new ReportTarget(ReportTargetType.POST, 5L, owner, blog, 5L, "owner"));
        when(reportRepository.saveAndFlush(any())).thenAnswer(i -> TestEntities.with(i.getArgument(0), "id", 99L));

        ReportCreatedResponse response = service.create(REPORTER,
                new CreateReportRequest("POST", 5L, "SPAM", "  광고\r\n글  "));

        assertThat(response).isEqualTo(new ReportCreatedResponse(99L, ReportStatus.PENDING));
        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReporter()).isSameAs(reporter);
        assertThat(saved.getValue().getTargetUser()).isSameAs(owner);
        assertThat(saved.getValue().getTargetBlog()).isSameAs(blog);
        assertThat(saved.getValue().getReason()).isEqualTo(ReportReason.SPAM);
        assertThat(saved.getValue().getDetail()).isEqualTo("광고\n글");
        verify(rateLimits).check(RateLimitKind.REPORT, "u:" + REPORTER);
    }

    @Test
    void blankDetailIsNullUnlessReasonIsOther() {
        when(postHandler.resolveForReporter(5L, REPORTER))
                .thenReturn(new ReportTarget(ReportTargetType.POST, 5L, owner, blog, 5L, "owner"));
        when(reportRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        service.create(REPORTER, new CreateReportRequest("POST", 5L, "ABUSE", "   "));
        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDetail()).isNull();

        expectField(() -> service.create(REPORTER, new CreateReportRequest("POST", 5L, "OTHER", " ")), "detail",
                "REQUIRED");
        expectField(() -> service.create(REPORTER, new CreateReportRequest("POST", 5L, "SPAM", "가".repeat(1001))),
                "detail", "TOO_LONG");
    }

    @Test
    void validatesTypeIdAndReasonBeforeAnything() {
        expectField(() -> service.create(REPORTER, new CreateReportRequest(null, 5L, "SPAM", null)), "targetType",
                "REQUIRED");
        expectField(() -> service.create(REPORTER, new CreateReportRequest("EXTERNAL_POST", 5L, "SPAM", null)),
                "targetType", "INVALID");
        expectField(() -> service.create(REPORTER, new CreateReportRequest("POST", null, "SPAM", null)), "targetId",
                "REQUIRED");
        expectField(() -> service.create(REPORTER, new CreateReportRequest("POST", 5L, null, null)), "reason",
                "REQUIRED");
        assertThatThrownBy(() -> service.create(REPORTER, new CreateReportRequest("POST", 5L, "spam", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    FieldError error = e.fieldErrors().getFirst();
                    assertThat(error.code()).isEqualTo("INVALID");
                    assertThat(error.params().get("allowed")).asString().contains("SPAM", "OTHER");
                });
        verify(rateLimits, never()).check(any(), anyString());
        verify(postHandler, never()).resolveForReporter(anyLong(), anyLong());
    }

    @Test
    void inactiveReporterAndRateLimit() {
        TestEntities.with(reporter, "status", UserStatus.SUSPENDED);
        expectCode(() -> service.create(REPORTER, new CreateReportRequest("POST", 5L, "SPAM", null)),
                ErrorCode.UNAUTHENTICATED);
        TestEntities.with(reporter, "status", UserStatus.ACTIVE);
        doThrow(BusinessException.retryAfter(ErrorCode.TOO_MANY_REQUESTS, "slow down", 60)).when(rateLimits)
                .check(RateLimitKind.REPORT, "u:" + REPORTER);
        expectCode(() -> service.create(REPORTER, new CreateReportRequest("POST", 5L, "SPAM", null)),
                ErrorCode.TOO_MANY_REQUESTS);
        verify(postHandler, never()).resolveForReporter(anyLong(), anyLong());
    }

    @Test
    void sameTargetTwiceIsConflict() {
        when(postHandler.resolveForReporter(5L, REPORTER))
                .thenReturn(new ReportTarget(ReportTargetType.POST, 5L, owner, blog, 5L, "owner"));
        when(reportRepository.existsByReporterIdAndTargetTypeAndTargetId(REPORTER, ReportTargetType.POST, 5L))
                .thenReturn(true, false);
        expectCode(() -> service.create(REPORTER, new CreateReportRequest("POST", 5L, "SPAM", null)),
                ErrorCode.REPORT_ALREADY_EXISTS);
        when(reportRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));
        expectCode(() -> service.create(REPORTER, new CreateReportRequest("POST", 5L, "SPAM", null)),
                ErrorCode.REPORT_ALREADY_EXISTS);
    }

    @Test
    void handlerErrorsPassThrough() {
        when(postHandler.resolveForReporter(5L, REPORTER)).thenThrow(
                new BusinessException(ErrorCode.CANNOT_REPORT_OWN_CONTENT, "own"));
        expectCode(() -> service.create(REPORTER, new CreateReportRequest("POST", 5L, "SPAM", null)),
                ErrorCode.CANNOT_REPORT_OWN_CONTENT);
        verify(reportRepository, never()).saveAndFlush(any());
    }

    static void expectField(Runnable call, String field, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fieldErrors()).singleElement().satisfies(f -> {
                assertThat(f.field()).isEqualTo(field);
                assertThat(f.code()).isEqualTo(code);
            });
        });
    }

    static void expectCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
