package net.java21.blog.backend.trackback.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Hidden;

import net.java21.blog.backend.trackback.TrackbackXml;
import net.java21.blog.backend.trackback.service.PingForm;
import net.java21.blog.backend.trackback.service.ReceiveOutcome;
import net.java21.blog.backend.trackback.service.TrackbackReceiveService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 트랙백 받기 {@code POST /{handle}/{postId}/trackback}(TrackBack 1.2, 005 contracts/api.md, research M13). {@code /api/v1} 밖이고
 * 공통 응답 틀을 쓰지 않으며 OpenAPI 문서에서 뺀다. 성공·실패 모두 HTTP 200 + {@code text/xml; charset=utf-8}.
 * <p>본문은 서블릿의 기본 해석(ISO-8859-1)을 쓰지 않고 원시 바이트를 {@code Content-Type}의 charset(없거나 모르면 UTF-8)으로 직접
 * 디코딩한다(EUC-KR 등 오래된 블로그). 본문에 없는 값은 쿼리 문자열에서 찾는다(일부 오래된 클라이언트).
 */
@Hidden
@RestController
public class TrackbackXmlController {

    /** 읽을 본문 최대 크기. 넘는 부분은 버린다(요약은 어차피 255자에서 자른다). */
    static final int MAX_BODY_BYTES = 256 * 1024;
    static final MediaType TEXT_XML_UTF8 = new MediaType("text", "xml", StandardCharsets.UTF_8);

    private final TrackbackReceiveService receiveService;

    public TrackbackXmlController(TrackbackReceiveService receiveService) {
        this.receiveService = receiveService;
    }

    @PostMapping("/{handle}/{postId:\\d{1,18}}/trackback")
    ResponseEntity<byte[]> receive(@PathVariable String handle, @PathVariable long postId,
            HttpServletRequest request) throws IOException {
        Charset charset = charset(request.getContentType());
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES);
        }
        Map<String, String> params = parseForm(new String(body, StandardCharsets.ISO_8859_1), charset);
        if (request.getQueryString() != null) {
            parseForm(request.getQueryString(), charset).forEach(params::putIfAbsent);
        }
        PingForm form = new PingForm(params.get("url"), params.get("title"), params.get("excerpt"),
                params.get("blog_name"));
        ReceiveOutcome outcome = receiveService.receive(handle, postId, form, request.getRemoteAddr());
        String xml = outcome.accepted() ? TrackbackXml.success() : TrackbackXml.error(outcome.message());
        return ResponseEntity.ok().contentType(TEXT_XML_UTF8).body(xml.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code Content-Type}의 charset. 없거나 지원하지 않으면 UTF-8. */
    static Charset charset(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            Charset charset = MediaType.parseMediaType(contentType).getCharset();
            return charset == null ? StandardCharsets.UTF_8 : charset;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * {@code application/x-www-form-urlencoded} 해석. {@code raw}는 바이트를 ISO-8859-1로 1:1 옮긴 문자열이며, %xx와 원시 바이트를 모두
     * 바이트로 되돌린 뒤 {@code charset}으로 디코딩한다. 같은 이름은 처음 값을 쓴다.
     */
    static Map<String, String> parseForm(String raw, Charset charset) {
        Map<String, String> params = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq), charset);
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1), charset);
            params.putIfAbsent(name.strip().toLowerCase(java.util.Locale.ROOT), value);
        }
        return params;
    }

    private static String decode(String latin1, Charset charset) {
        // %가 잘못 쓰인 값(예: "100%")도 버리지 않도록 직접 바이트로 되돌린다.
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(latin1.length());
        for (int i = 0; i < latin1.length(); i++) {
            char c = latin1.charAt(i);
            if (c == '+') {
                bytes.write(' ');
            } else if (c == '%' && i + 2 < latin1.length() && isHex(latin1.charAt(i + 1))
                    && isHex(latin1.charAt(i + 2))) {
                bytes.write(Integer.parseInt(latin1.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                bytes.write(c & 0xff);
            }
        }
        return new String(bytes.toByteArray(), charset);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }
}
