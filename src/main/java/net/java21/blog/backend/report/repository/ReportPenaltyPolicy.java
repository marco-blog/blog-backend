package net.java21.blog.backend.report.repository;

import static net.java21.blog.backend.report.domain.QReport.report;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.portal.service.BlogPenaltyPolicy;
import net.java21.blog.backend.portal.service.ScoreWeights;
import net.java21.blog.backend.report.ReportsProperties;
import net.java21.blog.backend.report.domain.ReportStatus;
import org.springframework.stereotype.Component;

/**
 * 인기 점수의 블로그 감점(003 FR-086, 005 research M6). 후보 블로그 중 최근 {@code blog.reports.penalty-window}(90일) 안에 인정된
 * (ACTIONED) 신고가 있는 블로그에 {@code 1 - reportPenalty} 배수를 준다. 기각·대기 신고, 대상 블로그가 없는 신고, 관리자 직접 숨김은
 * 감점하지 않는다. 블로그 수와 관계없이 쿼리 1회.
 */
@Component
public class ReportPenaltyPolicy implements BlogPenaltyPolicy {

    private final JPAQueryFactory queryFactory;
    private final ReportsProperties properties;
    private final Clock clock;

    public ReportPenaltyPolicy(JPAQueryFactory queryFactory, ReportsProperties properties, Clock clock) {
        this.queryFactory = queryFactory;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Map<Long, Double> penalties(Collection<Long> blogIds, ScoreWeights weights) {
        if (blogIds == null || blogIds.isEmpty()) {
            return Map.of();
        }
        Instant since = clock.instant().minus(properties.penaltyWindow());
        List<Long> penalized = queryFactory.selectDistinct(report.targetBlog.id)
                .from(report)
                .where(report.targetBlog.id.in(blogIds), report.status.eq(ReportStatus.ACTIONED),
                        report.handledAt.goe(since))
                .fetch();
        double multiplier = Math.max(0, Math.min(1, 1 - weights.reportPenalty()));
        Map<Long, Double> result = new HashMap<>();
        for (Long blogId : penalized) {
            result.put(blogId, multiplier);
        }
        return result;
    }
}
