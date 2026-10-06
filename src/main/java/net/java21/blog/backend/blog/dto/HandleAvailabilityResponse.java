package net.java21.blog.backend.blog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code GET /auth/handle-availability}: {@code { available: true }} 또는 {@code { available: false, reason }}.
 *
 * @param reason {@code TAKEN}(삭제된 블로그 포함), {@code RESERVED}, {@code INVALID}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandleAvailabilityResponse(boolean available, Reason reason) {

    public enum Reason {
        TAKEN,
        RESERVED,
        INVALID
    }

    public static HandleAvailabilityResponse ofAvailable() {
        return new HandleAvailabilityResponse(true, null);
    }

    public static HandleAvailabilityResponse unavailable(Reason reason) {
        return new HandleAvailabilityResponse(false, reason);
    }
}
