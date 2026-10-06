package net.java21.blog.backend.post.domain;

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
 * 글 일별 신호(post_daily_stats, 003 FR-086, research P4). 날짜는 UTC. 쓰기는 {@code PostDailyStatsRepository}의 원자적 upsert만
 * 쓰고 엔티티로 바꾸지 않는다. 90일 지난 행은 {@code PostStatsPurgeJob}이 지운다. 글을 영구 삭제하면 DB의 {@code ON DELETE CASCADE}로
 * 함께 지워진다.
 */
@Entity
@Table(name = "post_daily_stats")
public class PostDailyStat extends BaseTimeEntity {

    @EmbeddedId
    private PostDailyStatId id;

    @MapsId("postId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Post post;

    @Column(nullable = false)
    private int views;

    @Column(name = "read_completes", nullable = false)
    private int readCompletes;

    protected PostDailyStat() {
    }

    public PostDailyStat(Post post, java.time.LocalDate statDate, int views, int readCompletes) {
        this.post = post;
        this.id = new PostDailyStatId(post.getId(), statDate);
        this.views = views;
        this.readCompletes = readCompletes;
    }

    public PostDailyStatId getId() {
        return id;
    }

    public int getViews() {
        return views;
    }

    public int getReadCompletes() {
        return readCompletes;
    }
}
