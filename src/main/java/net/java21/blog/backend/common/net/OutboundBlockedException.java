package net.java21.blog.backend.common.net;

/** 밖으로 보내면 안 되는 주소(005 research M16). {@link #reason()}은 기록용 짧은 사유. */
public class OutboundBlockedException extends RuntimeException {

    /** 거부 사유. */
    public enum Reason {
        /** 형식(scheme·사용자 정보·호스트 없음·숫자 호스트). */
        INVALID_URL,
        /** 허용하지 않는 포트. */
        PORT_NOT_ALLOWED,
        /** 이름 해석 실패. */
        UNRESOLVABLE,
        /** 내부망·루프백·링크로컬 등. */
        BLOCKED_ADDRESS
    }

    private final Reason reason;

    public OutboundBlockedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
