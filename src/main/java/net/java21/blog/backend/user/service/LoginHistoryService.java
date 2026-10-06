package net.java21.blog.backend.user.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.regex.Pattern;

import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.user.domain.LoginHistory;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.dto.LoginHistoryResponse;
import net.java21.blog.backend.user.repository.LoginHistoryQueryRepository;
import net.java21.blog.backend.user.repository.LoginHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 기록(FR-139, quickstart #21). 로그인 서비스가 성공·실패마다 {@link #record}를 부르고,
 * 회원은 {@code GET /me/login-history}로 자기 기록을 최신순으로 본다. IP는 일부를 가려서만 내보낸다
 * (tasks.md "구현 전 결정 사항" 10번: IPv4는 뒤 두 자리, IPv6는 앞 3블록만).
 */
@Service
public class LoginHistoryService {

    /** 목록은 최신순 고정(정렬 매개변수는 받지 않는다). */
    public static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    static final String MASKED = "*";
    private static final Pattern IP_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    private final LoginHistoryRepository repository;
    private final LoginHistoryQueryRepository queryRepository;
    private final Clock clock;

    public LoginHistoryService(LoginHistoryRepository repository, LoginHistoryQueryRepository queryRepository,
            Clock clock) {
        this.repository = repository;
        this.queryRepository = queryRepository;
        this.clock = clock;
    }

    /**
     * 로그인 시도 한 번을 남긴다. 로그인 트랜잭션 안에서 부르며, 로그인 실패(예외)에도 남도록 로그인 서비스는
     * {@code BusinessException}에 롤백하지 않는다.
     *
     * @param user 시도한 회원. 없는 이메일이면 null
     */
    @Transactional
    public void record(User user, boolean success, ClientInfo client) {
        repository.save(new LoginHistory(user, success, blankToNull(client.ip()),
                truncate(blankToNull(client.userAgent()), LoginHistory.USER_AGENT_MAX), clock.instant()));
    }

    @Transactional(readOnly = true)
    public Page<LoginHistoryResponse> list(long userId, Pageable pageable) {
        return queryRepository.findByUserId(userId, pageable)
                .map(row -> new LoginHistoryResponse(row.at(), row.success(), maskIp(row.ip()), row.userAgent()));
    }

    /**
     * IP 일부 가림. IPv4 {@code a.b.*.*}, IPv6 앞 3블록 + {@code ::*}(예: {@code 2001:db8:85a3::*}).
     * IPv4가 담긴 IPv6(::ffff:a.b.c.d)는 IPv4로 본다. IP 표기가 아니면 {@code *}, null이면 null.
     */
    static String maskIp(String ip) {
        if (ip == null) {
            return null;
        }
        InetAddress address = parseLiteral(ip.strip());
        if (address == null) {
            return MASKED;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + ".*.*";
        }
        StringBuilder masked = new StringBuilder();
        for (int block = 0; block < 3; block++) {
            int value = ((bytes[block * 2] & 0xff) << 8) | (bytes[block * 2 + 1] & 0xff);
            masked.append(Integer.toHexString(value)).append(':');
        }
        return masked.append(":*").toString();
    }

    /** IP 표기만 해석한다(이름 조회 없음). 영역 ID({@code %eth0})는 버린다. */
    private static InetAddress parseLiteral(String value) {
        int zone = value.indexOf('%');
        String literal = zone >= 0 ? value.substring(0, zone) : value;
        if (literal.isEmpty() || !IP_CHARS.matcher(literal).matches()) {
            return null;
        }
        if (!literal.contains(":") && !isIpv4(literal)) {
            // 숫자 네 칸이 아니면 InetAddress가 이름으로 조회하려 하므로 미리 거른다.
            return null;
        }
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)
                    || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
