package net.java21.blog.backend.trackback.service;

/** 트랙백 받기 결과(005 contracts/api.md "트랙백 받기"). 실패 메시지는 TrackBack 1.2 응답에 그대로 쓰는 영어 고정 문구다. */
public enum ReceiveOutcome {
    ACCEPTED(null),
    NOT_ALLOWED("Trackback is not allowed"),
    MISSING_URL("Missing url"),
    INVALID_URL("Invalid url"),
    DUPLICATE("Duplicate trackback"),
    TOO_MANY_PINGS("Too many pings");

    private final String message;

    ReceiveOutcome(String message) {
        this.message = message;
    }

    public boolean accepted() {
        return this == ACCEPTED;
    }

    /** 실패 메시지(성공이면 null). */
    public String message() {
        return message;
    }
}
