package net.java21.blog.backend.mail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import net.java21.blog.backend.config.AsyncConfig;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.event.ReportResolvedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

/**
 * 메일 발송(research R23, FR-133, FR-148).
 * <ul>
 *   <li>문구는 받는 회원의 {@code locale}로 {@link MessageSource}({@code messages_*.properties})에서 만든다.
 *       미설정·지원하지 않는 언어는 en, 그 언어 파일에 키가 없으면 ko({@code I18nConfig}).</li>
 *   <li>요청 트랜잭션이 커밋된 뒤에만 비동기로 보낸다. 실패해도 요청 결과에는 영향이 없고 로그만 남긴다
 *       (받는 주소·토큰은 로그에 쓰지 않는다).</li>
 * </ul>
 */
@Service
public class MailService {

    static final String CONFIRM_PATH = "/password-reset/confirm?token=";
    /** 재설정 링크 유효 시간(분). 문구에 넣는다. */
    static final long RESET_LINK_MINUTES = net.java21.blog.backend.auth.domain.PasswordResetToken.TTL.toMinutes();

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final MessageSource messages;
    private final MailProperties properties;
    private final SiteProperties site;

    public MailService(JavaMailSender sender, MessageSource messages, MailProperties properties, SiteProperties site) {
        this.sender = sender;
        this.messages = messages;
        this.properties = properties;
        this.site = site;
    }

    @Async(AsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetMail mail) {
        try {
            sender.send(passwordResetMessage(mail));
            log.info("Password reset mail sent: userId={}", mail.userId());
        } catch (MailException | MessagingException e) {
            log.warn("Password reset mail failed: userId={}, error={}", mail.userId(), e.getMessage());
        }
    }

    private MimeMessage passwordResetMessage(PasswordResetMail mail) throws MessagingException {
        Locale locale = localeOf(mail.locale());
        String link = site.url(CONFIRM_PATH + mail.token());
        String siteName = messages.getMessage("mail.site-name", null, locale);
        Object[] args = {siteName, link, RESET_LINK_MINUTES};
        String subject = messages.getMessage("mail.passwordReset.subject", args, locale);
        String text = messages.getMessage("mail.passwordReset.body", args, locale);
        String html = "<p>" + HtmlUtils.htmlEscape(text).replace("\n", "<br>")
                .replace(HtmlUtils.htmlEscape(link), "<a href=\"" + HtmlUtils.htmlEscape(link) + "\">"
                        + HtmlUtils.htmlEscape(link) + "</a>")
                + "</p>";

        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.from());
        helper.setTo(mail.to());
        helper.setSubject(subject);
        helper.setText(text, html);
        return message;
    }

    /** 회원 언어 → 메일 언어. 미설정이면 en. */
    static Locale localeOf(String language) {
        return language == null || language.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(language);
    }

    /**
     * 005 권리 침해 신고 결과(research M10): 신고자는 비회원이라 언어를 모르므로 제목·본문에 ko와 en을 함께 쓴다. 결정(조치·미조치)과
     * 신고한 주소만 넣고 관리자 메모는 넣지 않는다. 받는 주소는 로그에 쓰지 않는다.
     */
    @Async(AsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRightsRequestResolved(ReportResolvedEvent event) {
        for (ReportResolvedEvent.RightsRecipient recipient : event.rights()) {
            try {
                sender.send(rightsRequestMessage(recipient, event.decision()));
                log.info("Rights request result mail sent: reportId={}", recipient.reportId());
            } catch (MailException | MessagingException e) {
                log.warn("Rights request result mail failed: reportId={}, error={}", recipient.reportId(),
                        e.getClass().getSimpleName());
            }
        }
    }

    private MimeMessage rightsRequestMessage(ReportResolvedEvent.RightsRecipient recipient, ReportStatus decision)
            throws MessagingException {
        String key = decision == ReportStatus.ACTIONED ? "actioned" : "dismissed";
        StringBuilder subject = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (Locale locale : List.of(Locale.KOREAN, Locale.ENGLISH)) {
            String siteName = messages.getMessage("mail.site-name", null, locale);
            Object[] args = {siteName, recipient.targetUrl()};
            if (!subject.isEmpty()) {
                subject.append(" / ");
                text.append("\n\n----------\n\n");
            }
            subject.append(messages.getMessage("mail.rightsRequest.subject", args, locale));
            text.append(messages.getMessage("mail.rightsRequest.body." + key, args, locale));
        }
        String html = "<p>" + HtmlUtils.htmlEscape(text.toString()).replace("\n", "<br>") + "</p>";
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.from());
        helper.setTo(recipient.contactEmail());
        helper.setSubject(subject.toString());
        helper.setText(text.toString(), html);
        return message;
    }
}
