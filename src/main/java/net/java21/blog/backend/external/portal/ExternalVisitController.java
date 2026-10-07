package net.java21.blog.backend.external.portal;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.web.VisitorKeyResolver;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 글 카드 링크(007 contracts/api.md 공개 API, research E14): 클릭을 세고 {@code 302 Location: 원문},
 * {@code Cache-Control: no-store}, {@code Referrer-Policy: no-referrer}(우리 서비스 경로를 외부에 넘기지 않음). GET이지만 부수 효과가
 * 있는 예외(contracts/api.md "설계 규칙과 다르게 만든 것") — robots.txt {@code Disallow: /api/}와 카드의 {@code nofollow}로 크롤러를
 * 막는다. 노출 조건을 만족하지 않거나 id가 숫자가 아니면 404 {@code EXTERNAL_POST_NOT_FOUND}. 비로그인 허용, 방문자 쿠키는 새로 만들지
 * 않는다(없으면 IP 해시로 센다).
 */
@RestController
public class ExternalVisitController {

    private final ExternalClickService clickService;
    private final VisitorKeyResolver visitorKeys;
    private final PersonalDataHasher hasher;

    public ExternalVisitController(ExternalClickService clickService, VisitorKeyResolver visitorKeys,
            PersonalDataHasher hasher) {
        this.clickService = clickService;
        this.visitorKeys = visitorKeys;
        this.hasher = hasher;
    }

    @GetMapping("/api/v1/external-posts/{id}/visit")
    ResponseEntity<Void> visit(@CurrentUser(required = false) AuthUser viewer, @PathVariable String id,
            HttpServletRequest request) {
        long postId = parseId(id);
        Long viewerId = viewer == null ? null : viewer.userId();
        String key = visitorKeys.peek(viewerId, request);
        if (key == null && request.getRemoteAddr() != null) {
            key = "ip:" + hasher.hash(request.getRemoteAddr());
        }
        String link = clickService.visit(postId, key);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private static long parseId(String raw) {
        if (raw != null && raw.matches("\\d{1,18}")) {
            long value = Long.parseLong(raw);
            if (value > 0) {
                return value;
            }
        }
        throw new BusinessException(ErrorCode.EXTERNAL_POST_NOT_FOUND, "External post not found: " + raw);
    }
}
