package net.java21.blog.backend.spam;

import net.java21.blog.backend.spam.captcha.CaptchaVerifier;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import org.springframework.stereotype.Component;

/**
 * 댓글·방명록 쓰기의 스팸 방어를 한 곳에서(005 FR-141~144, research M8·M9·M11·M12). 004 비회원 쓰기 자리({@code GuestWriteGuard}·
 * {@code RateLimitGuestWriteGuard})를 대체한다. 새 글 검사 순서:
 * <ol>
 *   <li>비회원 CAPTCHA(없거나 틀리면 400 {@code CAPTCHA_FAILED}, 회원은 검사하지 않음)</li>
 *   <li>속도(회원 ID 또는 비회원 IP, 넘으면 429 + {@code Retry-After})</li>
 *   <li>금칙어(비회원 이름은 이름류 400 field {@code guestName}, 내용은 REJECT 400 field {@code content} 또는 MASK 가림)</li>
 *   <li>반복 내용(422 {@code DUPLICATE_CONTENT_SPAM})</li>
 * </ol>
 * 수정은 금칙어만 본다. 관리자(ADMIN·SUPER_ADMIN)는 속도·반복을 세지 않는다(research M8).
 */
@Component
public class WriteGuard {

    /** 쓰는 곳. */
    public enum Kind {
        COMMENT(RateLimitKind.COMMENT),
        GUESTBOOK(RateLimitKind.GUESTBOOK);

        private final RateLimitKind rateLimitKind;

        Kind(RateLimitKind rateLimitKind) {
            this.rateLimitKind = rateLimitKind;
        }
    }

    /**
     * 작성자.
     *
     * @param member 로그인 회원(비회원 null)
     * @param ip     요청 IP(비회원 속도·반복 주체, CAPTCHA {@code remoteip})
     */
    public record Writer(User member, String ip) {

        public static Writer member(User member, String ip) {
            return new Writer(member, ip);
        }

        public static Writer guest(String ip) {
            return new Writer(null, ip);
        }

        boolean guest() {
            return member == null;
        }

        boolean exempt() {
            return member != null && isExempt(member);
        }

        String subject() {
            return member == null ? "ip:" + (ip == null ? "" : ip) : "u:" + member.getId();
        }
    }

    private final CaptchaVerifier captcha;
    private final RateLimitPolicy rateLimits;
    private final BannedWordMatcher bannedWords;
    private final DuplicateContentDetector duplicates;

    public WriteGuard(CaptchaVerifier captcha, RateLimitPolicy rateLimits, BannedWordMatcher bannedWords,
            DuplicateContentDetector duplicates) {
        this.captcha = captcha;
        this.rateLimits = rateLimits;
        this.bannedWords = bannedWords;
        this.duplicates = duplicates;
    }

    /** 관리자는 속도 제한·반복 검사를 받지 않는다. */
    public static boolean isExempt(User user) {
        return user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.SUPER_ADMIN;
    }

    /**
     * 새 댓글·방명록 글.
     *
     * @param content   정리한 내용
     * @param guestName 비회원 이름(회원은 무시)
     * @return 저장할 내용(MASK 금칙어를 가린 값)
     */
    public String guardNew(Kind kind, Writer writer, String content, String captchaToken, String guestName) {
        if (writer.guest()) {
            captcha.verify(captchaToken, writer.ip());
        }
        if (!writer.exempt()) {
            rateLimits.check(kind.rateLimitKind, writer.subject());
        }
        if (writer.guest()) {
            bannedWords.requireCleanName("guestName", guestName);
        }
        String filtered = bannedWords.filterContent("content", content);
        if (!writer.exempt()) {
            duplicates.check(writer.subject(), content);
        }
        return filtered;
    }

    /** 내용 수정: 금칙어만(속도·반복은 세지 않음). */
    public String guardEdit(String content) {
        return bannedWords.filterContent("content", content);
    }
}
