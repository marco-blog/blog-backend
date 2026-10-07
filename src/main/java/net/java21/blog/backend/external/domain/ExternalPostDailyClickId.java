package net.java21.blog.backend.external.domain;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** {@link ExternalPostDailyClick}의 복합 키(external_post_id, click_date). */
@Embeddable
public record ExternalPostDailyClickId(
        @Column(name = "external_post_id") Long externalPostId,
        @Column(name = "click_date") LocalDate clickDate) implements Serializable {
}
