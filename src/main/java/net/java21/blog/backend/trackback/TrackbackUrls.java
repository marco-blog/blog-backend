package net.java21.blog.backend.trackback;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.trackback.domain.Trackback;
import org.springframework.stereotype.Component;

/**
 * 트랙백 주소 규칙(005 research M13·M14·M15).
 * <ul>
 *   <li>{@link #normalize}: http/https, 호스트 필수, 사용자 정보 없음, 1000자 이하. 스킴·호스트를 소문자로, 기본 포트·조각을 지우고 경로·쿼리는
 *       그대로(끝 {@code /} 유지, 빈 경로는 {@code /}).</li>
 *   <li>{@link #hash}: 정규화한 주소의 SHA-256(16진수 64자) — 같은 글 중복 확인({@code uk_trackbacks_post_source_url_hash}).</li>
 *   <li>{@link #internalTarget}: {@code blog.base-url}과 같은 스킴·호스트·포트의 {@code /{handle}/{postId}}(끝에 {@code /trackback}
 *       허용)면 서비스 안 글.</li>
 * </ul>
 */
@Component
public class TrackbackUrls {

    private static final Pattern POST_PATH = Pattern.compile("^/([^/]+)/(\\d{1,18})(?:/trackback)?/?$");

    private final SiteProperties site;

    public TrackbackUrls(SiteProperties site) {
        this.site = site;
    }

    /** 정규화한 주소와 그 해시. {@code original}은 앞뒤 공백만 뗀 받은 값. */
    public record Normalized(String original, String normalized, String hash) {
    }

    /** 서비스 안 글 주소. */
    public record InternalTarget(String handle, long postId) {
    }

    /** 형식이 맞으면 정규화한 값, 아니면 빈 값. */
    public static Optional<Normalized> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String original = raw.strip();
        if (original.isEmpty() || original.length() > Trackback.URL_MAX) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(original);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme) || uri.isOpaque() || uri.getRawUserInfo() != null) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        StringBuilder normalized = new StringBuilder(original.length()).append(scheme).append("://")
                .append(host.toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port != -1 && !(port == 80 && "http".equals(scheme)) && !(port == 443 && "https".equals(scheme))) {
            normalized.append(':').append(port);
        }
        String path = uri.getRawPath();
        normalized.append(path == null || path.isEmpty() ? "/" : path);
        if (uri.getRawQuery() != null) {
            normalized.append('?').append(uri.getRawQuery());
        }
        String value = normalized.toString();
        return Optional.of(new Normalized(original, value, hash(value)));
    }

    /** SHA-256 16진수(소문자 64자). */
    public static String hash(String normalized) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 서비스 안 글 주소면 그 handle과 글 번호. 다른 호스트·포트·경로, 숫자가 아닌 글 번호는 빈 값. */
    public Optional<InternalTarget> internalTarget(String url) {
        URI uri;
        URI base;
        try {
            uri = new URI(url.strip());
            base = new URI(site.baseUrl());
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
        if (uri.getHost() == null || base.getHost() == null || uri.getScheme() == null
                || !uri.getScheme().equalsIgnoreCase(base.getScheme())
                || !uri.getHost().equalsIgnoreCase(base.getHost()) || effectivePort(uri) != effectivePort(base)) {
            return Optional.empty();
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        if (!basePath.isEmpty()) {
            if (!path.startsWith(basePath + "/")) {
                return Optional.empty();
            }
            path = path.substring(basePath.length());
        }
        Matcher matcher = POST_PATH.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new InternalTarget(matcher.group(1), Long.parseLong(matcher.group(2))));
    }

    /** 글 주소 {@code {base-url}/{handle}/{postId}}. */
    public String postUrl(String handle, long postId) {
        return site.url("/" + handle + "/" + postId);
    }

    /** 트랙백 받는 주소 {@code {base-url}/{handle}/{postId}/trackback}. */
    public String trackbackUrl(String handle, long postId) {
        return postUrl(handle, postId) + "/trackback";
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }
}
