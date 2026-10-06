package net.java21.blog.backend.user.repository;

import static net.java21.blog.backend.user.domain.QLoginHistory.loginHistory;

import java.time.Instant;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

/** 로그인 기록 조회(회원별 최신순 페이지)와 보관 기간이 지난 기록 정리(FR-139). */
@Repository
public class LoginHistoryQueryRepository {

    private final JPAQueryFactory queryFactory;

    public LoginHistoryQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 회원의 로그인 기록을 최신순(시각, 같으면 id 역순)으로. 정렬은 고정이며 {@code pageable}의 정렬은 쓰지 않는다.
     * DTO projection 1회 + 개수 1회(인덱스 {@code idx_login_history_user_created}).
     */
    public Page<LoginHistoryRow> findByUserId(Long userId, Pageable pageable) {
        List<LoginHistoryRow> content = queryFactory
                .select(Projections.constructor(LoginHistoryRow.class,
                        loginHistory.createdAt, loginHistory.success, loginHistory.ip, loginHistory.userAgent))
                .from(loginHistory)
                .where(loginHistory.user.id.eq(userId))
                .orderBy(loginHistory.createdAt.desc(), loginHistory.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        return PageableExecutionUtils.getPage(content, pageable, () -> queryFactory
                .select(loginHistory.count())
                .from(loginHistory)
                .where(loginHistory.user.id.eq(userId))
                .fetchOne());
    }

    /** {@code created_at < cutoff}인 기록 id(오래된 순, 최대 {@code limit}개). */
    public List<Long> findIdsCreatedBefore(Instant cutoff, int limit) {
        return queryFactory.select(loginHistory.id)
                .from(loginHistory)
                .where(loginHistory.createdAt.lt(cutoff))
                .orderBy(loginHistory.createdAt.asc(), loginHistory.id.asc())
                .limit(limit)
                .fetch();
    }

    public long deleteByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return queryFactory.delete(loginHistory).where(loginHistory.id.in(ids)).execute();
    }
}
