package net.java21.blog.backend.stats.domain;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** {@link BlogDailyVisit}의 복합 키(blog_id, visit_date). */
@Embeddable
public record BlogDailyVisitId(
        @Column(name = "blog_id") Long blogId,
        @Column(name = "visit_date") LocalDate visitDate) implements Serializable {
}
