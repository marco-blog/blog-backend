package net.java21.blog.backend.common.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 서버가 밖으로 보내는 요청의 주소 검사(SSRF 방어, 005 research M16). http/https, 허용 포트, 사용자 정보 없음, 그리고 이름을 해석한 모든 주소가
 * 공인 주소여야 한다(하나라도 루프백·사설·링크로컬·CGNAT·멀티캐스트·미지정이면 거부). 10진수·16진수 같은 숫자 호스트 표기는 거부한다.
 * {@code allow-private}(시험용)이면 주소 종류 검사만 건너뛴다. 해석한 주소를 돌려주므로 호출하는 쪽은 그 주소로 연결해 재해석 틈(DNS
 * rebinding)을 줄일 수 있다.
 */
public class OutboundUrlGuard {

    private static final Set<String> SCHEMES = Set.of("http", "https");
    /** 점이 없는 숫자·16진수 호스트(예: {@code 2130706433}, {@code 0x7f000001}) 또는 점 표기 안 8진수·16진수 조각. */
    private static final Pattern NUMERIC_HOST = Pattern.compile("^(0x[0-9a-f]+|[0-9]+)$|(^|\\.)(0x[0-9a-f]*|0[0-9]+)(\\.|$)");

    private final OutboundProperties properties;
    private final HostResolver resolver;

    public OutboundUrlGuard(OutboundProperties properties, HostResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    /** 검사를 통과한 주소와 그 이름의 해석 결과. */
    public record Target(URI uri, String host, int port, List<InetAddress> addresses) {
    }

    /**
     * @throws OutboundBlockedException 허용하지 않는 주소
     */
    public Target check(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getScheme() == null
                || !SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new OutboundBlockedException(OutboundBlockedException.Reason.INVALID_URL, "Only http and https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new OutboundBlockedException(OutboundBlockedException.Reason.INVALID_URL, "User info not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new OutboundBlockedException(OutboundBlockedException.Reason.INVALID_URL, "Missing host");
        }
        host = host.toLowerCase(Locale.ROOT);
        boolean ipv6Literal = host.startsWith("[");
        if (ipv6Literal) {
            host = host.substring(1, host.length() - 1);
        } else if (!isDottedQuad(host) && (host.matches("[0-9.]+") || NUMERIC_HOST.matcher(host).find())) {
            throw new OutboundBlockedException(OutboundBlockedException.Reason.INVALID_URL, "Numeric host notation");
        }
        int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        if (!properties.portSet().contains(port)) {
            throw new OutboundBlockedException(OutboundBlockedException.Reason.PORT_NOT_ALLOWED, "Port not allowed");
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new OutboundBlockedException(OutboundBlockedException.Reason.UNRESOLVABLE, "Unknown host");
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new OutboundBlockedException(OutboundBlockedException.Reason.UNRESOLVABLE, "Unknown host");
        }
        if (!properties.allowPrivate()) {
            for (InetAddress address : addresses) {
                if (isInternal(address)) {
                    throw new OutboundBlockedException(OutboundBlockedException.Reason.BLOCKED_ADDRESS,
                            "Address not allowed");
                }
            }
        }
        return new Target(uri, host, port, List.copyOf(addresses));
    }

    /** 공인 주소가 아닌지. */
    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0                                       // 0.0.0.0/8
                    || first == 127
                    || (first == 100 && second >= 64 && second <= 127) // CGNAT 100.64/10
                    || (first == 169 && second == 254)
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24
                    || (first == 198 && (second == 18 || second == 19))    // 벤치마크 198.18/15
                    || first >= 240;                                    // 예약·브로드캐스트
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {                               // 고유 로컬 fc00::/7
                return true;
            }
            if (first == 0xfe && ((b[1] & 0xc0) == 0x80 || (b[1] & 0xc0) == 0xc0)) { // fe80::/10, fec0::/10
                return true;
            }
            if (isMappedOrCompatibleV4(b)) {
                try {
                    return isInternal(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x02) {                // 6to4 2002::/16 → 안의 IPv4
                try {
                    return isInternal(InetAddress.getByAddress(new byte[] {b[2], b[3], b[4], b[5]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            if (first == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
                return true;                                            // NAT64 64:ff9b::/96
            }
        }
        return false;
    }

    private static boolean isMappedOrCompatibleV4(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0);
    }

    private static boolean isDottedQuad(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)
                    || (part.length() > 1 && part.charAt(0) == '0') || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
