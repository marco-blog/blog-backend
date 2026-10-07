package net.java21.blog.backend.common.security;

/**
 * 비밀번호 시도 제한의 대상(004 research B3): 보호 글, 비회원 댓글, 비회원 방명록 글. 대상마다 실패 수를 따로 센다.
 */
public record AttemptTarget(Kind kind, long id) {

    public enum Kind {
        POST,
        COMMENT,
        GUESTBOOK
    }

    public static AttemptTarget post(long id) {
        return new AttemptTarget(Kind.POST, id);
    }

    public static AttemptTarget comment(long id) {
        return new AttemptTarget(Kind.COMMENT, id);
    }

    public static AttemptTarget guestbook(long id) {
        return new AttemptTarget(Kind.GUESTBOOK, id);
    }

    String key() {
        return kind.name() + ":" + id;
    }
}
