package net.java21.blog.backend.external.feed;

/** 피드로 읽을 수 없음({@code PARSE_ERROR}). */
public class FeedParseException extends Exception {

    public FeedParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
