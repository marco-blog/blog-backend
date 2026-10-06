package net.java21.blog.backend.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import net.java21.blog.backend.auth.repository.PasswordResetTokenRepository;
import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.auth.service.PasswordResetService;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.i18n.I18nConfig;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 메일 언어(T230, US5 AS5, quickstart #27, FR-148): 비밀번호 재설정을 요청한 회원의 {@code locale}이 그대로 메일 언어가 된다.
 * 요청({@link PasswordResetService}) → 발송 이벤트({@link PasswordResetMail}) → 발송({@link MailService})까지
 * 실제 메일 문구({@link I18nConfig})로 잇고, 외부 SMTP 대신 {@link JavaMailSender}를 흉내 낸다.
 * <ul>
 *   <li>{@code locale}이 en이면 영어 메일, 다른 3개 언어도 각자 언어.</li>
 *   <li>{@code locale}이 NULL(미설정)이면 기본 en.</li>
 *   <li>회원이 언어 설정을 바꾸면({@code PATCH /me} {@code locale}) 다음 메일부터 새 언어.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MailLocaleTest {

    private static final String EMAIL = "marco@example.com";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ApplicationEventPublisher events;
    @Mock
    private JavaMailSender sender;

    private PasswordResetService resetService;
    private MailService mailService;
    private User member;

    @BeforeEach
    void setUp() {
        resetService = new PasswordResetService(userRepository, tokenRepository, refreshTokenRepository,
                passwordEncoder, TestEntities.HASHER, events, new MutableClock(Instant.parse("2026-10-06T04:00:00Z")));
        mailService = new MailService(sender, new I18nConfig().messageSource(),
                new MailProperties("localhost", 1025, null, null, "no-reply@blog.test", false),
                new SiteProperties("https://blog.example.test/"));
        member = TestEntities.user(7L, EMAIL, "{hash}", "marco");
        when(userRepository.findByEmailHash(TestEntities.HASHER.hashEmail(EMAIL))).thenReturn(Optional.of(member));
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    /** 재설정 요청이 낸 이벤트를 발송기에 그대로 넘기고, 실제로 보낸 메일을 돌려준다. */
    private MimeMessage requestResetMail() {
        resetService.request(EMAIL);
        ArgumentCaptor<PasswordResetMail> event = ArgumentCaptor.forClass(PasswordResetMail.class);
        verify(events).publishEvent(event.capture());
        mailService.onPasswordResetRequested(event.getValue());
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        return sent.getValue();
    }

    @Test
    void englishMemberGetsEnglishMail() throws Exception {
        TestEntities.with(member, "locale", "en");

        MimeMessage mail = requestResetMail();

        assertThat(mail.getSubject()).isEqualTo("Reset your password");
        assertThat(plainText(mail)).startsWith("Hello from Blog.")
                .contains("set a new password")
                .contains("https://blog.example.test/password-reset/confirm?token=")
                .doesNotContainPattern("[가-힣]");
    }

    @Test
    void memberWithoutLocaleGetsEnglishMailByDefault() throws Exception {
        TestEntities.with(member, "locale", null);

        MimeMessage mail = requestResetMail();

        assertThat(mail.getSubject()).isEqualTo("Reset your password");
        assertThat(plainText(mail)).startsWith("Hello from Blog.").doesNotContainPattern("[가-힣]");
    }

    @ParameterizedTest
    @CsvSource({
            "ko, 비밀번호 재설정 안내, 비밀번호",
            "ja, パスワード再設定のご案内, パスワード",
            "zh-CN, 重置密码, 密码"
    })
    void otherLanguagesFollowMemberLocale(String locale, String subject, String bodyWord) throws Exception {
        TestEntities.with(member, "locale", locale);

        MimeMessage mail = requestResetMail();

        assertThat(mail.getSubject()).isEqualTo(subject);
        assertThat(plainText(mail)).contains(bodyWord).doesNotContain("set a new password");
    }

    @Test
    void changedLanguageSettingAppliesToNextMail() throws Exception {
        TestEntities.with(member, "locale", "ko");
        member.changeLocale("en");

        MimeMessage mail = requestResetMail();

        assertThat(mail.getSubject()).isEqualTo("Reset your password");
    }

    /** multipart/alternative의 첫 부분(일반 텍스트) */
    private static String plainText(MimeMessage message) throws MessagingException, IOException {
        Object content = message.getContent();
        while (content instanceof Multipart multipart) {
            content = multipart.getBodyPart(0).getContent();
        }
        return String.valueOf(content);
    }
}
