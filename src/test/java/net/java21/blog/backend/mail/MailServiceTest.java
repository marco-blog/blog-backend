package net.java21.blog.backend.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.i18n.I18nConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 비밀번호 재설정 메일(T128, FR-133, FR-148, research R23): 회원 {@code locale}의 {@code MessageSource} 문구로 제목·본문
 * (없으면 en, 그 언어에 키가 없으면 ko), 링크는 {@code blog.base-url} + {@code /password-reset/confirm?token=},
 * 커밋 뒤 비동기로 보내고 실패는 로그만 남긴다. 외부 SMTP 없이 {@link JavaMailSender}를 흉내 낸다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MailServiceTest {

    private static final MailProperties PROPS = new MailProperties("localhost", 1025, null, null,
            "no-reply@blog.test", false);

    @Mock
    private JavaMailSender sender;

    private MailService service;

    @BeforeEach
    void setUp() {
        service = new MailService(sender, new I18nConfig().messageSource(), PROPS,
                new SiteProperties("https://blog.example.test/"));
    }

    private MimeMessage send(String locale) throws Exception {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        service.onPasswordResetRequested(new PasswordResetMail(7L, "marco@example.com", locale, "tok_en-123"));
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "ko, 비밀번호 재설정 안내",
            "en, Reset your password",
            "ja, パスワード再設定のご案内",
            "zh-CN, 重置密码",
            "NULL, Reset your password",
            "fr, Reset your password"
    })
    void subjectInMemberLocaleWithEnglishFallback(String locale, String subject) throws Exception {
        MimeMessage message = send(locale);

        assertThat(message.getSubject()).isEqualTo(subject);
    }

    @Test
    void bodyHasLinkRecipientAndSender() throws Exception {
        MimeMessage message = send("ko");

        assertThat(message.getRecipients(Message.RecipientType.TO))
                .containsExactly(new InternetAddress("marco@example.com"));
        assertThat(message.getFrom()).containsExactly(new InternetAddress("no-reply@blog.test"));
        List<String> parts = textParts(message);
        assertThat(parts).hasSize(2);
        assertThat(parts).allSatisfy(text -> assertThat(text)
                .contains("https://blog.example.test/password-reset/confirm?token=tok_en-123")
                .contains("30"));
        assertThat(parts.get(0)).contains("비밀번호");
        assertThat(parts.get(1)).contains("<a href=\"https://blog.example.test/password-reset/confirm?token=tok_en-123\"");
    }

    @Test
    void sendFailureIsOnlyLogged(CapturedOutput output) throws Exception {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("smtp down")).when(sender).send(any(MimeMessage.class));

        assertThatCode(() -> service.onPasswordResetRequested(
                new PasswordResetMail(7L, "marco@example.com", "ko", "tok"))).doesNotThrowAnyException();

        assertThat(output).contains("Password reset mail failed").contains("userId=7")
                .doesNotContain("marco@example.com").doesNotContain("token=tok");
    }

    @Test
    void sentAfterCommitAsynchronously() throws NoSuchMethodException {
        Method handler = MailService.class.getMethod("onPasswordResetRequested", PasswordResetMail.class);

        assertThat(handler.getAnnotation(Async.class)).isNotNull();
        assertThat(handler.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    private static List<String> textParts(Part part) throws MessagingException, IOException {
        List<String> texts = new ArrayList<>();
        Object content = part.getContent();
        if (content instanceof String text) {
            texts.add(text);
        } else if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart body = multipart.getBodyPart(i);
                texts.addAll(textParts(body));
            }
        }
        return texts;
    }
}
