package net.java21.blog.backend.report.service;

import static net.java21.blog.backend.report.service.ReportServiceTest.expectCode;
import static net.java21.blog.backend.report.service.ReportServiceTest.expectField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.RightsRequestRequest;
import net.java21.blog.backend.report.repository.ReportRepository;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.spam.captcha.CaptchaVerifier;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 005 T031: 권리 침해 신고. 사유는 네 가지, http/https 주소, 근거 필수·2000자, CAPTCHA 실패면 저장 없음, IP 한도, 주소가 서비스 안
 * 콘텐츠면 대상을 채우고 아니면 비워 둔다. 신고자는 없다.
 */
@ExtendWith(MockitoExtension.class)
class RightsRequestServiceTest {

    private static final String IP = "203.0.113.7";
    private static final String URL = "https://blog.java21.net/owner/5";

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private ReportUrlResolver urlResolver;
    @Mock
    private CaptchaVerifier captcha;
    @Mock
    private RateLimitPolicy rateLimits;

    private RightsRequestService service() {
        return new RightsRequestService(reportRepository, urlResolver, captcha, rateLimits);
    }

    @Test
    void resolvedUrlFillsTheTarget() {
        User owner = TestEntities.user(1L);
        Blog blog = TestEntities.blog(10L, owner, "owner");
        when(urlResolver.resolve(URL)).thenReturn(Optional.of(new ReportTarget(ReportTargetType.POST, 5L, owner,
                blog, 5L, "owner")));

        service().submit(request(URL, "COPYRIGHT", "제 사진입니다", " me@example.com "), IP);

        InOrder order = inOrder(captcha, rateLimits, reportRepository);
        order.verify(captcha).verify("token", IP);
        order.verify(rateLimits).check(RateLimitKind.RIGHTS_REQUEST, "ip:" + IP);
        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        order.verify(reportRepository).save(saved.capture());
        Report report = saved.getValue();
        assertThat(report.getChannel()).isEqualTo(ReportChannel.RIGHTS_REQUEST);
        assertThat(report.getReporter()).isNull();
        assertThat(report.getTargetType()).isEqualTo(ReportTargetType.POST);
        assertThat(report.getTargetId()).isEqualTo(5L);
        assertThat(report.getTargetUser()).isSameAs(owner);
        assertThat(report.getTargetUrl()).isEqualTo(URL);
        assertThat(report.getReason()).isEqualTo(ReportReason.COPYRIGHT);
        assertThat(report.getContactEmail()).isEqualTo("me@example.com");
    }

    @Test
    void unresolvedUrlLeavesTheTargetForAdmins() {
        when(urlResolver.resolve("http://elsewhere.example/x")).thenReturn(Optional.empty());
        service().submit(request(" http://elsewhere.example/x ", "OTHER", "근거", "me@example.com"), null);
        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().hasTarget()).isFalse();
        verify(rateLimits).check(RateLimitKind.RIGHTS_REQUEST, "ip:");
    }

    @Test
    void validationHappensBeforeCaptcha() {
        RightsRequestService service = service();
        expectField(() -> service.submit(request(URL, "SPAM", "근거", "me@example.com"), IP), "reason", "INVALID");
        expectField(() -> service.submit(request("ftp://x.example/a", "PRIVACY", "근거", "me@example.com"), IP),
                "targetUrl", "INVALID");
        expectField(() -> service.submit(request("not a url", "PRIVACY", "근거", "me@example.com"), IP),
                "targetUrl", "INVALID");
        expectField(() -> service.submit(request("https:///nohost", "PRIVACY", "근거", "me@example.com"), IP),
                "targetUrl", "INVALID");
        expectField(() -> service.submit(request(URL, "DEFAMATION", " ", "me@example.com"), IP), "rightsBasis",
                "REQUIRED");
        expectField(() -> service.submit(request(URL, "DEFAMATION", "가".repeat(2001), "me@example.com"), IP),
                "rightsBasis", "TOO_LONG");
        verify(captcha, never()).verify(any(), any());
        assertThat(RightsRequestService.targetUrl("https://x.example/" + "a".repeat(10))).startsWith("https://");
    }

    @Test
    void captchaFailureSavesNothing() {
        doThrow(new BusinessException(ErrorCode.CAPTCHA_FAILED, "bad")).when(captcha).verify("token", IP);
        expectCode(() -> service().submit(request(URL, "PRIVACY", "근거", "me@example.com"), IP),
                ErrorCode.CAPTCHA_FAILED);
        verify(rateLimits, never()).check(any(), anyString());
        verify(reportRepository, never()).save(any());
    }

    private static RightsRequestRequest request(String url, String reason, String basis, String email) {
        return new RightsRequestRequest(url, reason, basis, email, "token");
    }
}
