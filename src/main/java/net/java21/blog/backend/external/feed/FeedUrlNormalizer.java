package net.java21.blog.backend.external.feed;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 피드·원문 주소 정규화와 해시(007 research E6). 스킴을 지워 http·https를 같은 주소로 보고, 호스트는 소문자·IDN은 ASCII, 기본 포트(80·443)·
 * 끝 {@code /}·조각을 지운다. 쿼리는 순서까지 그대로 둔다. {@code feed_url_hash}·{@code link_hash}·인증 대상 해시가 모두 이 함수다.
 */
public final class FeedUrlNormalizer {

    private FeedUrlNormalizer() {
    }

    /** 정규화한 문자열(예: {@code example.com/feed?a=1}). 주소가 아니면 {@link IllegalArgumentException}. */
    public static String normalize(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Empty URL");
        }
        String s = url.strip();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        int schemeEnd = s.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException("Absolute URL required");
        }
        String rest = s.substring(schemeEnd + 3);
        int authorityEnd = indexOfAny(rest, '/', '?');
        String authority = authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
        String pathAndQuery = authorityEnd < 0 ? "" : rest.substring(authorityEnd);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        String host;
        String port = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Bad IPv6 host");
            }
            host = authority.substring(0, close + 1).toLowerCase(Locale.ROOT);
            if (close + 1 < authority.length() && authority.charAt(close + 1) == ':') {
                port = authority.substring(close + 2);
            }
        } else {
            int colon = authority.lastIndexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? null : authority.substring(colon + 1);
            host = asciiHost(host);
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Missing host");
        }
        StringBuilder out = new StringBuilder(host);
        if (port != null && !port.isEmpty() && !port.equals("80") && !port.equals("443")) {
            if (!port.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("Bad port");
            }
            out.append(':').append(Integer.parseInt(port));
        }
        int q = pathAndQuery.indexOf('?');
        String path = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String query = q < 0 ? null : pathAndQuery.substring(q);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        out.append(path);
        if (query != null && query.length() > 1) {
            out.append(query);
        }
        return out.toString();
    }

    /** SHA-256(정규화) 16진수 64자. */
    public static String hash(String url) {
        return sha256(normalize(url));
    }

    /** 정규화하지 않은 문자열의 SHA-256(guid용). */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 회원이 넣은 주소를 요청할 수 있는 {@link URI}로(IDN 호스트는 punycode, 스킴이 없으면 https). 형식이 아니면
     * {@link IllegalArgumentException}.
     */
    public static URI toUri(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Empty URL");
        }
        String s = input.strip();
        if (!s.contains("://")) {
            s = "https://" + s;
        }
        int schemeEnd = s.indexOf("://");
        String scheme = s.substring(0, schemeEnd);
        String rest = s.substring(schemeEnd + 3);
        int authorityEnd = indexOfAny(rest, '/', '?', '#');
        String authority = authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
        String tail = authorityEnd < 0 ? "" : rest.substring(authorityEnd);
        int at = authority.lastIndexOf('@');
        String userInfo = at >= 0 ? authority.substring(0, at + 1) : "";
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        if (!hostPort.startsWith("[")) {
            int colon = hostPort.lastIndexOf(':');
            String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            String port = colon < 0 ? "" : hostPort.substring(colon);
            hostPort = asciiHost(host) + port;
        }
        try {
            URI uri = new URI(scheme + "://" + userInfo + hostPort + encodeLoose(tail));
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("Missing host");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Bad URL", e);
        }
    }

    /** 주소 문자열에 섞인 공백·비 ASCII 문자를 퍼센트 인코딩(이미 인코딩된 {@code %xx}는 그대로). */
    static String encodeLoose(String s) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xff;
            if (c <= 0x20 || c >= 0x7f || c == '"' || c == '<' || c == '>' || c == '\\' || c == '^' || c == '`'
                    || c == '{' || c == '|' || c == '}') {
                sb.append('%').append(String.format("%02X", c));
            } else {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    private static String asciiHost(String host) {
        String h = host.strip();
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        try {
            return IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Bad host", e);
        }
    }

    private static int indexOfAny(String s, char... chars) {
        int min = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (min < 0 || i < min)) {
                min = i;
            }
        }
        return min;
    }
}
