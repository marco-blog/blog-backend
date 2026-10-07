package net.java21.blog.backend.admin.report;

import static net.java21.blog.backend.report.domain.QReport.report;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 관리자 신고 목록·상세 조회(005 research M3). 묶음 목록은 쿼리 2회(묶음 페이지, 묶음 수) — 대상 수와 관계없다. 사유별 수는 같은 쿼리의
 * 조건부 합계로 센다. 대상이 정해진 신고는 (종류, id)로, 대상 미정 권리 침해 신고는 한 건씩 묶는다.
 */
@Repository
public class ReportQueryRepository {

    /** 상세의 같은 대상 신고 최대 수. */
    public static final int SAME_TARGET_LIMIT = 100;

    private static final QUser reporter = new QUser("reporter");
    private static final ReportReason[] REASONS = ReportReason.values();

    private final JPAQueryFactory queryFactory;

    public ReportQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 목록 조건. {@code status}는 필수, 나머지는 null이면 거르지 않는다. */
    public record Filter(ReportStatus status, ReportTargetType targetType, ReportChannel channel) {
    }

    public Page<ReportGroupRow> findGroups(Filter filter, Pageable pageable) {
        BooleanExpression where = where(filter);
        NumberExpression<Long> untargetedKey = new CaseBuilder().when(report.targetType.isNull()).then(report.id)
                .otherwise(0L);
        List<Expression<?>> select = new ArrayList<>(List.of(report.id.min(), report.targetType, report.targetId,
                report.count(), report.createdAt.min(), report.createdAt.max(), report.channel.min(),
                report.channel.max(), report.action.max()));
        for (ReportReason reason : REASONS) {
            select.add(new CaseBuilder().when(report.reason.eq(reason)).then(1L).otherwise(0L).sumLong());
        }
        List<Tuple> rows = queryFactory.select(select.toArray(Expression[]::new))
                .from(report)
                .where(where)
                .groupBy(report.targetType, report.targetId, untargetedKey)
                .orderBy(report.createdAt.min().asc(), report.id.min().asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        List<ReportGroupRow> groups = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            Map<ReportReason, Long> reasons = new EnumMap<>(ReportReason.class);
            for (int i = 0; i < REASONS.length; i++) {
                Number count = (Number) row.get(9 + i, Object.class);
                if (count != null && count.longValue() > 0) {
                    reasons.put(REASONS[i], count.longValue());
                }
            }
            groups.add(new ReportGroupRow(row.get(0, Long.class), row.get(1, ReportTargetType.class),
                    row.get(2, Long.class), row.get(3, Long.class), row.get(4, java.time.Instant.class),
                    row.get(5, java.time.Instant.class), row.get(6, ReportChannel.class),
                    row.get(7, ReportChannel.class), row.get(8, net.java21.blog.backend.report.domain.ReportAction.class),
                    reasons));
        }
        Long total = queryFactory.select(groupKey().countDistinct()).from(report).where(where).fetchOne();
        return new PageImpl<>(groups, pageable, total == null ? 0 : total);
    }

    /** 대기 중인 신고 묶음 수(콘솔 메뉴 배지). 쿼리 1회. */
    public long countPendingGroups() {
        Long count = queryFactory.select(groupKey().countDistinct()).from(report)
                .where(report.status.eq(ReportStatus.PENDING)).fetchOne();
        return count == null ? 0 : count;
    }

    /** 신고와 신고자·처리자(상세의 첫 줄). */
    public Report findWithPeople(long id) {
        QUser handler = new QUser("handler");
        return queryFactory.selectFrom(report)
                .leftJoin(report.reporter, reporter).fetchJoin()
                .leftJoin(report.handledBy, handler).fetchJoin()
                .where(report.id.eq(id))
                .fetchOne();
    }

    /** 같은 대상의 신고(최신순 {@link #SAME_TARGET_LIMIT}건, 신고자 LEFT JOIN). 쿼리 1회. */
    public List<Report> findSameTarget(ReportTargetType type, Long targetId) {
        return queryFactory.selectFrom(report)
                .leftJoin(report.reporter, reporter).fetchJoin()
                .where(report.targetType.eq(type), report.targetId.eq(targetId))
                .orderBy(report.createdAt.desc(), report.id.desc())
                .limit(SAME_TARGET_LIMIT)
                .fetch();
    }

    /** 이 회원이 대상 작성자로 받은 신고 수(006 FR-104). 쿼리 1회. */
    public long countReceivedBy(long userId) {
        Long count = queryFactory.select(report.count()).from(report).where(report.targetUser.id.eq(userId))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /**
     * 묶음 열쇠(숫자 하나): 대상 미정이면 {@code -id}, 정해졌으면 {@code targetId * 8 + 종류 번호}. {@code COUNT(DISTINCT ...)}로 묶음 수를
     * 센다.
     */
    private static NumberExpression<Long> groupKey() {
        CaseBuilder.Cases<Long, NumberExpression<Long>> ordinal = null;
        ReportTargetType[] types = ReportTargetType.values();
        for (int i = 0; i < types.length; i++) {
            ordinal = ordinal == null
                    ? new CaseBuilder().when(report.targetType.eq(types[i])).then((long) i)
                    : ordinal.when(report.targetType.eq(types[i])).then((long) i);
        }
        NumberExpression<Long> typeNumber = ordinal.otherwise(0L);
        return new CaseBuilder().when(report.targetType.isNull()).then(report.id.negate())
                .otherwise(report.targetId.multiply(8L).add(typeNumber));
    }

    private static BooleanExpression where(Filter filter) {
        BooleanExpression where = report.status.eq(filter.status());
        if (filter.targetType() != null) {
            where = where.and(report.targetType.eq(filter.targetType()));
        }
        if (filter.channel() != null) {
            where = where.and(report.channel.eq(filter.channel()));
        }
        return where;
    }
}
