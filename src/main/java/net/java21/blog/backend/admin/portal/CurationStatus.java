package net.java21.blog.backend.admin.portal;

import java.time.Instant;

/** 추천의 지금 상태(003 contracts/api.md {@code Curation.status}). 노출 기간은 {@code [startsAt, endsAt)}. */
public enum CurationStatus {
    ACTIVE,
    UPCOMING,
    ENDED;

    public static CurationStatus of(Instant startsAt, Instant endsAt, Instant now) {
        if (now.isBefore(startsAt)) {
            return UPCOMING;
        }
        return now.isBefore(endsAt) ? ACTIVE : ENDED;
    }
}
