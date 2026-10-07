package net.java21.blog.backend.spam.captcha;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cloudflare Turnstile 서버 검증(005 research M9). {@code siteverify}에 {@code secret}·{@code response}·{@code remoteip}를 form으로
 * 보내고 {@code success: true}일 때만 통과한다. 시간 초과·5xx·잘못된 응답은 실패로 본다(닫힌 쪽으로 실패). 토큰이 없거나 비면 부르지 않는다.
 * secret은 로그에 남기지 않는다.
 */
public class TurnstileCaptchaVerifier implements CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileCaptchaVerifier.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_TOKEN_LENGTH = 2048;

    private final CaptchaProperties properties;
    private final HttpClient client;

    public TurnstileCaptchaVerifier(CaptchaProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.verifyTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void verify(String token, String ip) {
        if (CaptchaFailures.isBlank(token) || token.length() > MAX_TOKEN_LENGTH) {
            throw CaptchaFailures.failed("missing token");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("secret", properties.secretKey());
        form.put("response", token);
        if (ip != null && !ip.isBlank()) {
            form.put("remoteip", ip);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.verifyUrl()))
                .timeout(properties.verifyTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Turnstile verification unavailable: {}", e.getClass().getSimpleName());
            throw CaptchaFailures.failed("unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CaptchaFailures.failed("interrupted");
        }
        if (response.statusCode() != 200) {
            log.warn("Turnstile verification returned HTTP {}", response.statusCode());
            throw CaptchaFailures.failed("http " + response.statusCode());
        }
        boolean success;
        try {
            JsonNode body = JSON.readTree(response.body());
            success = body != null && body.path("success").asBoolean(false);
        } catch (JacksonException e) {
            log.warn("Turnstile verification returned an invalid body");
            throw CaptchaFailures.failed("invalid response");
        }
        if (!success) {
            throw CaptchaFailures.failed("rejected");
        }
    }

    @Override
    public CaptchaProperties.Provider provider() {
        return CaptchaProperties.Provider.TURNSTILE;
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
