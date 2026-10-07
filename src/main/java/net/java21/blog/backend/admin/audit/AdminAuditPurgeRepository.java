package net.java21.blog.backend.admin.audit;

import static net.java21.blog.backend.admin.audit.QAdminAuditLog.adminAuditLog;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import org.springframework.stereotype.Repository;

/**
 * 작업 기록 1년 정리 전용(006 research A7). 작업 기록의 유일한 삭제 경로다({@link AdminAuditLogRepository}에는 삭제가 없다).
 * {@link AdminAuditLog}가 {@code @Immutable}이라 엔티티 삭제 대신 id 묶음의 벌크 DELETE를 쓴다(H2·MySQL 같은 SQL).
 */
@Repository
public class AdminAuditPurgeRepository {

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    public AdminAuditPurgeRepository(JPAQueryFactory queryFactory, EntityManager entityManager) {
        this.queryFactory = queryFactory;
        this.entityManager = entityManager;
    }

    /** {@code cutoff} 전에 남긴 기록의 id(오래된 것부터 {@code limit}개, 인덱스 {@code idx_admin_audit_logs_created}). */
    public List<Long> findIdsCreatedBefore(Instant cutoff, int limit) {
        return queryFactory.select(adminAuditLog.id)
                .from(adminAuditLog)
                .where(adminAuditLog.createdAt.lt(cutoff))
                .orderBy(adminAuditLog.createdAt.asc(), adminAuditLog.id.asc())
                .limit(limit)
                .fetch();
    }

    public long deleteByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return entityManager.createNativeQuery("DELETE FROM admin_audit_logs WHERE id IN (:ids)")
                .setParameter("ids", ids)
                .executeUpdate();
    }
}
