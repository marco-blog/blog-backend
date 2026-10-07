package net.java21.blog.backend.spam.captcha;

import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** CAPTCHA 설정(005 contracts/api.md "CAPTCHA"). 비로그인 허용, 1시간 공유 캐시. secret은 응답하지 않는다. */
@RestController
public class CaptchaController {

    /** {@code siteKey}는 turnstile일 때만. */
    public record CaptchaConfigResponse(String provider, String siteKey) {
    }

    private final CaptchaProperties properties;

    public CaptchaController(CaptchaProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/v1/captcha/config")
    ResponseEntity<ApiResponse<CaptchaConfigResponse>> config() {
        boolean turnstile = properties.provider() == CaptchaProperties.Provider.TURNSTILE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(ApiResponse.ok(new CaptchaConfigResponse(properties.provider().value(),
                        turnstile ? properties.siteKey() : null)));
    }
}
