package net.java21.blog.backend.report.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.text.PlainTextNormalizer;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.dto.RightsRequestRequest;
import net.java21.blog.backend.report.repository.ReportRepository;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.spam.captcha.CaptchaVerifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 권리 침해 신고(005 FR-040, research M2). 비회원 양식이라 로그인 상태로 보내도 {@code reporter_id}는 NULL. CAPTCHA 필수(실패 400
 * {@code CAPTCHA_FAILED}, 저장 없음), IP당 1시간 {@code blog.reports.rights-request-per-ip-per-hour}건(429). 주소가 서비스 안 콘텐츠면
 * 대상을 채우고({@link ReportUrlResolver}), 아니면 관리자가 지정한다. 연락 이메일은 암호화해 저장한다.
 */
@Service
public class RightsRequestService {

    private static final List<ReportReason> REASONS = ReportReason.RIGHTS_REQUEST_REASONS.stream().sorted().toList();

    private final ReportRepository reportRepository;
    private final ReportUrlResolver urlResolver;
    private final CaptchaVerifier captcha;
    private final RateLimitPolicy rateLimits;

    public RightsRequestService(ReportRepository reportRepository, ReportUrlResolver urlResolver,
            CaptchaVerifier captcha, RateLimitPolicy rateLimits) {
        this.reportRepository = reportRepository;
        this.urlResolver = urlResolver;
        this.captcha = captcha;
        this.rateLimits = rateLimits;
    }

    @Transactional
    public void submit(RightsRequestRequest request, String ip) {
        ReportReason reason = ReportService.reason(request.reason(), REASONS);
        String targetUrl = targetUrl(request.targetUrl());
        String basis = PlainTextNormalizer.multiline(request.rightsBasis());
        if (basis.isEmpty()) {
            throw ReportService.invalid(FieldError.of("rightsBasis", "REQUIRED"));
        }
        if (basis.codePointCount(0, basis.length()) > Report.RIGHTS_BASIS_MAX) {
            throw ReportService.invalid(new FieldError("rightsBasis", "TOO_LONG", Map.of("max",
                    Report.RIGHTS_BASIS_MAX)));
        }
        captcha.verify(request.captchaToken(), ip);
        rateLimits.check(RateLimitKind.RIGHTS_REQUEST, "ip:" + (ip == null ? "" : ip));
        Report report = Report.rightsRequest(targetUrl, reason, basis, request.contactEmail().strip());
        urlResolver.resolve(targetUrl).ifPresent(target -> report.assignTarget(target.type(), target.id(),
                target.targetUser(), target.targetBlog()));
        reportRepository.save(report);
    }

    /** http/https 절대 주소만(1000자 이하는 요청 검증이 본다). */
    static String targetUrl(String raw) {
        String url = raw == null ? "" : raw.strip();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null
                    && url.length() <= Report.TARGET_URL_MAX) {
                return url;
            }
        } catch (URISyntaxException e) {
            // 아래에서 INVALID
        }
        throw ReportService.invalid(FieldError.of("targetUrl", "INVALID"));
    }
}
