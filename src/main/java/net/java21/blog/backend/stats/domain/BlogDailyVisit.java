package net.java21.blog.backend.stats.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.domain.BaseTimeEntity;

/**
 * 블로그 일별 방문자 수(blog_daily_visits, 004 FR-067, research B9). 날짜는 {@code blog.stats.time-zone} 기준이다. 쓰기는
 * {@code BlogVisitRepository}의 원자적 upsert만 쓰고 엔티티로 바꾸지 않는다. 행은 지우지 않는다(결정 표 15번, 블로그 영구 정리 때만).
 */
@Entity
@Table(name = "blog_daily_visits")
public class BlogDailyVisit extends BaseTimeEntity {

    @EmbeddedId
    private BlogDailyVisitId id;

    @MapsId("blogId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blog_id")
    private Blog blog;

    @Column(nullable = false)
    private int visitors;

    protected BlogDailyVisit() {
    }

    public BlogDailyVisit(Blog blog, LocalDate visitDate, int visitors) {
        this.blog = blog;
        this.id = new BlogDailyVisitId(blog.getId(), visitDate);
        this.visitors = visitors;
    }

    public BlogDailyVisitId getId() {
        return id;
    }

    public LocalDate getVisitDate() {
        return id.visitDate();
    }

    public int getVisitors() {
        return visitors;
    }
}
