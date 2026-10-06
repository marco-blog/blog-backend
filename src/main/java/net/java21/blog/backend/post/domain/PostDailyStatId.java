package net.java21.blog.backend.post.domain;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** {@link PostDailyStat}의 복합 키(post_id, stat_date). */
@Embeddable
public record PostDailyStatId(
        @Column(name = "post_id") Long postId,
        @Column(name = "stat_date") LocalDate statDate) implements Serializable {
}
