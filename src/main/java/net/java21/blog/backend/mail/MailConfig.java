package net.java21.blog.backend.mail;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 메일 발송기(research R23). Spring Boot의 {@code spring.mail.*} 대신 {@code blog.mail.*}({@link MailProperties})로
 * {@link JavaMailSenderImpl}을 직접 만든다. 기동 때 SMTP에 접속하지 않는다(메일 health 지표는 application.yml에서 끈다).
 */
@Configuration(proxyBeanMethods = false)
public class MailConfig {

    /** SMTP 접속·응답 대기 한도(ms). 느린 서버가 발송 스레드를 오래 붙잡지 않게 한다. */
    static final String TIMEOUT_MS = "10000";

    @Bean
    JavaMailSenderImpl javaMailSender(MailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties javaMail = sender.getJavaMailProperties();
        javaMail.put("mail.transport.protocol", "smtp");
        javaMail.put("mail.smtp.starttls.enable", String.valueOf(properties.starttls()));
        javaMail.put("mail.smtp.starttls.required", String.valueOf(properties.starttls()));
        javaMail.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        javaMail.put("mail.smtp.timeout", TIMEOUT_MS);
        javaMail.put("mail.smtp.writetimeout", TIMEOUT_MS);
        if (properties.hasCredentials()) {
            sender.setUsername(properties.username());
            sender.setPassword(properties.password());
            javaMail.put("mail.smtp.auth", "true");
        }
        return sender;
    }
}
