package net.java21.blog.backend.common.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 믿는 프록시 목록({@code blog.security.trusted-proxies}). 항목은 IP({@code 127.0.0.1}, {@code ::1}) 또는
 * CIDR({@code 10.0.0.0/8}, {@code fd00::/8}). IPv4에 대응하는 IPv6 표기({@code ::ffff:127.0.0.1})는 같은 주소로 본다.
 * 문자열이 IP 표기가 아니면 이름 조회(DNS) 없이 믿지 않는다.
 */
public final class TrustedProxies {

    private static final Pattern IPV4 = Pattern.compile(
            "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    private final List<Range> ranges;

    private TrustedProxies(List<Range> ranges) {
        this.ranges = ranges;
    }

    /** 설정 값을 읽는다. 잘못된 항목이 있으면 기동 때 바로 실패한다. */
    public static TrustedProxies of(List<String> entries) {
        List<Range> ranges = new ArrayList<>();
        if (entries != null) {
            for (String entry : entries) {
                ranges.add(Range.parse(entry.strip()));
            }
        }
        return new TrustedProxies(List.copyOf(ranges));
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public boolean contains(String address) {
        InetAddress parsed = parse(address);
        return parsed != null && ranges.stream().anyMatch(range -> range.contains(parsed));
    }

    /** IPv4 점 표기 또는 IPv6 표기인지(이름·포트·zone 없이). */
    static boolean isIpLiteral(String value) {
        return parse(value) != null;
    }

    /** IP 표기만 {@link InetAddress}로 바꾼다. 이름은 조회하지 않고 null. */
    static InetAddress parse(String value) {
        if (value == null) {
            return null;
        }
        boolean ipv4 = IPV4.matcher(value).matches();
        boolean ipv6 = value.indexOf(':') >= 0 && IPV6_CHARS.matcher(value).matches();
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            // IP 표기만 넘기므로 DNS 조회가 일어나지 않는다.
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private record Range(byte[] network, int prefixLength) {

        static Range parse(String entry) {
            int slash = entry.indexOf('/');
            String address = slash < 0 ? entry : entry.substring(0, slash);
            InetAddress parsed = TrustedProxies.parse(address);
            if (parsed == null) {
                throw invalid(entry);
            }
            byte[] bytes = parsed.getAddress();
            int max = bytes.length * 8;
            int prefix = max;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw invalid(entry);
                }
                if (prefix < 0 || prefix > max) {
                    throw invalid(entry);
                }
            }
            return new Range(bytes, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefixLength % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        private static IllegalArgumentException invalid(String entry) {
            return new IllegalArgumentException(
                    "blog.security.trusted-proxies: not an IP address or CIDR: '" + entry + "'");
        }
    }
}
