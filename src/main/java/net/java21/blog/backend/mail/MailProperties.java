package net.java21.blog.backend.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SMTP 발송 설정(contracts/api.md "프로퍼티", research R23). 비밀번호는 환경 변수로만 준다.
 * 개발은 Mailpit(localhost:1025, 인증·STARTTLS 없음), 테스트는 {@code JavaMailSender}를 흉내 낸다.
 *
 * @param host     SMTP 서버(필수)
 * @param port     SMTP 포트(필수)
 * @param username SMTP 인증 아이디(없으면 인증하지 않음)
 * @param password SMTP 인증 비밀번호(환경 변수)
 * @param from     보내는 사람 주소(필수)
 * @param starttls STARTTLS 사용(기본 true)
 */
@ConfigurationProperties("blog.mail")
public record MailProperties(String host, Integer port, String username, String password, String from,
        @DefaultValue("true") boolean starttls) {

    public MailProperties {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("blog.mail.host is required");
        }
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("blog.mail.port is required (1-65535)");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("blog.mail.from is required");
        }
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }

    /** 비밀번호가 로그에 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "MailProperties[host=" + host + ", port=" + port + ", from=" + from + ", starttls=" + starttls
                + ", auth=" + hasCredentials() + "]";
    }
}
