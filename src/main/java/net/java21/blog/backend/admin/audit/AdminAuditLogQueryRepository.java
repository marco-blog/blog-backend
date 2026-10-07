package net.java21.blog.backend.admin.audit;

import static net.java21.blog.backend.admin.audit.QAdminAuditLog.adminAuditLog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.admin.audit.dto.AuditLogEntryResponse;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 작업 기록 조회(006 FR-106, research A6). 조건은 인덱스 4개({@code created_at}, {@code (admin_id, created_at)},
 * {@code (action, created_at)}, {@code (target_type, target_id)})에 맞춘 where이고, 목록은 관리자 닉네임 JOIN projection으로
 * 쿼리 2회(목록 + 개수), 새 것 먼저(같은 시각은 id 내림차순). 요청 IP(암호문)는 목록에서 읽지 않는다.
 */
@Repository
public class AdminAuditLogQueryRepository {

    private static final QUser admin = new QUser("auditAdmin");

    private final JPAQueryFactory queryFactory;

    public AdminAuditLogQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 조회 조건. {@code from}·{@code to}는 [from, to) 시각 범위(필수), 나머지는 null·빈 목록이면 조건 없음.
     */
    public record Criteria(Instant from, Instant to, Long adminId, List<String> actions, String targetType,
            Long targetId, String targetKey) {

        public Criteria {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public Page<AuditLogEntryResponse> search(Criteria criteria, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder()
                .and(adminAuditLog.createdAt.goe(criteria.from()))
                .and(adminAuditLog.createdAt.lt(criteria.to()));
        if (criteria.adminId() != null) {
            where.and(adminAuditLog.admin.id.eq(criteria.adminId()));
        }
        if (!criteria.actions().isEmpty()) {
            where.and(adminAuditLog.action.in(criteria.actions()));
        }
        if (criteria.targetType() != null) {
            where.and(adminAuditLog.targetType.eq(criteria.targetType()));
        }
        if (criteria.targetId() != null) {
            where.and(adminAuditLog.targetId.eq(criteria.targetId()));
        }
        if (criteria.targetKey() != null) {
            where.and(adminAuditLog.targetKey.eq(criteria.targetKey()));
        }
        List<Tuple> tuples = queryFactory
                .select(adminAuditLog.id, admin.id, admin.nickname, adminAuditLog.action, adminAuditLog.targetType,
                        adminAuditLog.targetId, adminAuditLog.targetKey, adminAuditLog.before, adminAuditLog.after,
                        adminAuditLog.reason, adminAuditLog.createdAt)
                .from(adminAuditLog)
                .join(adminAuditLog.admin, admin)
                .where(where)
                .orderBy(adminAuditLog.createdAt.desc(), adminAuditLog.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        List<AuditLogEntryResponse> rows = new ArrayList<>();
        for (Tuple t : tuples) {
            rows.add(new AuditLogEntryResponse(t.get(adminAuditLog.id),
                    new AuditLogEntryResponse.AdminName(t.get(admin.id), t.get(admin.nickname)),
                    t.get(adminAuditLog.action), t.get(adminAuditLog.targetType), t.get(adminAuditLog.targetId),
                    t.get(adminAuditLog.targetKey), t.get(adminAuditLog.before), t.get(adminAuditLog.after),
                    t.get(adminAuditLog.reason), t.get(adminAuditLog.createdAt)));
        }
        Long total = queryFactory.select(adminAuditLog.count())
                .from(adminAuditLog)
                .where(where)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 기록 하나와 관리자(쿼리 1회, 요청 IP는 엔티티 변환기가 복호화한다). */
    public Optional<AdminAuditLog> findWithAdmin(long id) {
        return Optional.ofNullable(queryFactory.selectFrom(adminAuditLog)
                .join(adminAuditLog.admin, admin).fetchJoin()
                .where(adminAuditLog.id.eq(id))
                .fetchOne());
    }
}
