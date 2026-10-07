package net.java21.blog.backend.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.event.ReportResolvedEvent;
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
import org.springframework.context.MessageSource;
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

    /** 005 권리 침해 결과 메일(T042): ko·en을 함께, 결정과 신고한 주소만, 받는 주소는 로그에 없음, 실패는 다음 수신자를 막지 않음. */
    @Test
    void rightsRequestResultIsBilingual(CapturedOutput output) throws Exception {
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("smtp down")).doNothing().when(sender).send(any(MimeMessage.class));

        service.onRightsRequestResolved(new ReportResolvedEvent(null, ReportStatus.ACTIONED,
                List.of(new ReportResolvedEvent.MemberRecipient(1L, 2L)),
                List.of(new ReportResolvedEvent.RightsRecipient(41L, "first@example.com", "https://x.example/a"),
                        new ReportResolvedEvent.RightsRecipient(42L, "me@example.com", "https://x.example/<b>"))));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(2)).send(captor.capture());
        MimeMessage message = captor.getAllValues().get(1);
        assertThat(message.getRecipients(Message.RecipientType.TO)).containsExactly(new InternetAddress(
                "me@example.com"));
        assertThat(message.getSubject()).contains(" / ");
        List<String> parts = textParts(message);
        assertThat(parts.get(0)).contains("https://x.example/<b>").contains("----------");
        assertThat(parts.get(1)).contains("&lt;b&gt;").doesNotContain("<b>");
        assertThat(output).contains("Rights request result mail failed: reportId=41")
                .contains("Rights request result mail sent: reportId=42").doesNotContain("me@example.com")
                .doesNotContain("first@example.com");
    }

    @Test
    void dismissedRightsRequestUsesTheDismissedBody() throws Exception {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        service.onRightsRequestResolved(new ReportResolvedEvent(ReportTargetType.POST, ReportStatus.DISMISSED,
                List.of(), List.of(new ReportResolvedEvent.RightsRecipient(41L, "me@example.com", "https://x/a"))));
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        String text = textParts(captor.getValue()).get(0);
        MessageSource messages = new I18nConfig().messageSource();
        String siteKo = messages.getMessage("mail.site-name", null, Locale.KOREAN);
        assertThat(text).contains(messages.getMessage("mail.rightsRequest.body.dismissed",
                new Object[] {siteKo, "https://x/a"}, Locale.KOREAN));

        Method handler = MailService.class.getMethod("onRightsRequestResolved", ReportResolvedEvent.class);
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
