package net.java21.blog.backend.external.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 외부 글 일별 클릭(external_post_daily_clicks, 007 FR-124). 날짜는 UTC. 쓰기는 {@code ExternalPostDailyClickRepository}의 upsert만
 * 쓴다. 글이 지워지면 DB의 {@code ON DELETE CASCADE}로 함께 지워진다. 90일 지난 행은 정리 작업이 지운다.
 */
@Entity
@Table(name = "external_post_daily_clicks")
public class ExternalPostDailyClick extends BaseTimeEntity {

    @EmbeddedId
    private ExternalPostDailyClickId id;

    @MapsId("externalPostId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "external_post_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ExternalPost externalPost;

    @Column(nullable = false)
    private int clicks;

    protected ExternalPostDailyClick() {
    }

    public ExternalPostDailyClick(ExternalPost post, LocalDate date, int clicks) {
        this.externalPost = post;
        this.id = new ExternalPostDailyClickId(post.getId(), date);
        this.clicks = clicks;
    }

    public ExternalPostDailyClickId getId() {
        return id;
    }

    public int getClicks() {
        return clicks;
    }
}
