package net.java21.blog.backend.trackback.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import net.java21.blog.backend.common.net.OutboundBlockedException;
import net.java21.blog.backend.common.net.OutboundUrlGuard;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.trackback.TrackbackText;
import net.java21.blog.backend.trackback.domain.PingErrorCode;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import org.springframework.stereotype.Component;

/**
 * 밖으로 트랙백 보내기(005 FR-052, research M15·M16). {@link OutboundUrlGuard}를 통과한 주소에만 JDK {@link HttpClient}(리다이렉트를
 * 따르지 않음, 연결·응답 시간 제한)로 {@code application/x-www-form-urlencoded; charset=utf-8} POST를 보내고, 응답 본문은
 * {@value #MAX_RESPONSE_BYTES}바이트까지만 읽어 DOCTYPE·외부 엔터티를 끈 파서로 {@code <error>}를 읽는다. 재시도하지 않는다.
 * <ul>
 *   <li>{@code <error>0</error>} → 성공</li>
 *   <li>2xx가 아님 → {@code HTTP_ERROR}("HTTP {상태}"), 연결 실패도 {@code HTTP_ERROR}</li>
 *   <li>{@code <error>1</error>}·XML 아님 → {@code REMOTE_ERROR}(상대 {@code <message>}를 태그 제거해 255자)</li>
 *   <li>시간 초과 → {@code TIMEOUT}, 내부망·허용하지 않는 포트 → {@code BLOCKED_ADDRESS}, 형식·이름 해석 실패 → {@code INVALID_URL}</li>
 * </ul>
 */
@Component
public class TrackbackPinger {

    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    static final String USER_AGENT = "blog.java21.net-trackback/1.0";
    private static final Pattern ERROR_TAG = Pattern.compile("<error>\\s*(\\d+)\\s*</error>");
    private static final Pattern MESSAGE_TAG = Pattern.compile("(?s)<message>(.*?)</message>");

    /** 보낼 값(글 주소·제목·요약·블로그 이름). */
    public record Payload(String url, String title, String excerpt, String blogName) {
    }

    /** 결과. {@code code}가 null이면 성공. */
    public record Result(PingErrorCode code, String message) {

        static final Result SUCCESS = new Result(null, null);

        public boolean success() {
            return code == null;
        }

        static Result failure(PingErrorCode code, String message) {
            return new Result(code, message);
        }
    }

    private final OutboundUrlGuard guard;
    private final TrackbackProperties properties;
    private final HttpClient client;

    public TrackbackPinger(OutboundUrlGuard guard, TrackbackProperties properties) {
        this.guard = guard;
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    public Result ping(String targetUrl, Payload payload) {
        URI uri;
        try {
            uri = new URI(targetUrl.strip());
        } catch (URISyntaxException e) {
            return Result.failure(PingErrorCode.INVALID_URL, "Invalid url");
        }
        try {
            guard.check(uri);
        } catch (OutboundBlockedException e) {
            return switch (e.reason()) {
                case BLOCKED_ADDRESS, PORT_NOT_ALLOWED -> Result.failure(PingErrorCode.BLOCKED_ADDRESS, e.getMessage());
                case INVALID_URL, UNRESOLVABLE -> Result.failure(PingErrorCode.INVALID_URL, e.getMessage());
            };
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(form(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            return Result.failure(PingErrorCode.TIMEOUT, "Timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure(PingErrorCode.TIMEOUT, "Interrupted");
        } catch (IOException | IllegalArgumentException e) {
            return Result.failure(PingErrorCode.HTTP_ERROR, "Connection failed");
        }
        byte[] body;
        try (InputStream in = response.body()) {
            body = in.readNBytes(MAX_RESPONSE_BYTES);
        } catch (HttpTimeoutException e) {
            return Result.failure(PingErrorCode.TIMEOUT, "Timed out");
        } catch (IOException e) {
            return Result.failure(PingErrorCode.HTTP_ERROR, "Connection failed");
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return Result.failure(PingErrorCode.HTTP_ERROR, "HTTP " + status);
        }
        return parse(body);
    }

    /** TrackBack 응답 해석. XML로 읽지 못하면(앞에 경고 문구가 붙은 응답 등) {@code <error>} 태그만 찾는다. */
    static Result parse(byte[] body) {
        String error;
        String message;
        try {
            Document document = builder().parse(new InputSource(new ByteArrayInputStream(body)));
            error = text(document, "error");
            message = text(document, "message");
        } catch (SAXException | IOException e) {
            String raw = new String(body, StandardCharsets.UTF_8);
            Matcher matcher = ERROR_TAG.matcher(raw);
            if (!matcher.find() || raw.contains("<!DOCTYPE") || raw.contains("<!ENTITY")) {
                return Result.failure(PingErrorCode.REMOTE_ERROR, "Invalid response");
            }
            error = matcher.group(1);
            Matcher messageMatcher = MESSAGE_TAG.matcher(raw);
            message = messageMatcher.find() ? messageMatcher.group(1) : null;
        }
        if (error == null) {
            return Result.failure(PingErrorCode.REMOTE_ERROR, "Invalid response");
        }
        if ("0".equals(error.strip())) {
            return Result.SUCCESS;
        }
        String cleaned = TrackbackText.plain(message, TrackbackPingLog.MESSAGE_MAX);
        return Result.failure(PingErrorCode.REMOTE_ERROR, cleaned == null ? "Remote error" : cleaned);
    }

    private static String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    /** DOCTYPE·외부 엔터티·XInclude를 끈 파서(XXE 방지). */
    static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String form(Payload payload) {
        StringBuilder form = new StringBuilder();
        append(form, "url", payload.url());
        append(form, "title", TrackbackText.truncate(payload.title(), Trackback.TEXT_MAX));
        append(form, "excerpt", TrackbackText.truncate(payload.excerpt(), Trackback.TEXT_MAX));
        append(form, "blog_name", TrackbackText.truncate(payload.blogName(), Trackback.TEXT_MAX));
        return form.toString();
    }

    private static void append(StringBuilder form, String name, String value) {
        if (value == null) {
            return;
        }
        if (!form.isEmpty()) {
            form.append('&');
        }
        form.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
