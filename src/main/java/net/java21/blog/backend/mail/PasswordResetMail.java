package net.java21.blog.backend.mail;

/**
 * 비밀번호 재설정 메일 발송 요청(이벤트). {@code PasswordResetService}가 트랜잭션 안에서 내고,
 * {@link MailService}가 커밋 뒤 비동기로 보낸다(research R23).
 *
 * @param to     받는 주소(정규화한 이메일)
 * @param locale 회원의 화면 언어(ko·en·ja·zh-CN, null=미설정 → en)
 * @param token  재설정 토큰 원문. 메일 링크에만 쓰고 저장·로그하지 않는다
 */
public record PasswordResetMail(long userId, String to, String locale, String token) {

    /** 이메일과 토큰 원문이 로그에 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "PasswordResetMail[userId=" + userId + ", locale=" + locale + "]";
    }
}
