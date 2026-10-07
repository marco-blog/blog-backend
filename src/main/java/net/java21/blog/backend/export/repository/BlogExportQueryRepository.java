package net.java21.blog.backend.export.repository;

import static net.java21.blog.backend.export.domain.QBlogExport.blogExport;

import java.time.Instant;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.domain.ExportStatus;
import org.springframework.stereotype.Repository;

/**
 * 블로그 백업 행 조회와 조건부 UPDATE(004 research B14). 각 메서드는 쿼리 1회다. 생성 작업은 가장 오래된 PENDING 하나를
 * 조건부 UPDATE로 RUNNING으로 가져와(0행이면 다른 실행이 먼저 가져감) 한 번에 하나씩 만든다.
 */
@Repository
public class BlogExportQueryRepository {

    /** 블로그 백업 목록 길이(contracts/api.md: 최근 10건). */
    public static final int LIST_LIMIT = 10;

    private final JPAQueryFactory queryFactory;

    public BlogExportQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** {@code since} 이후 만든 실패하지 않은 백업이 있는지(하루 제한, {@code idx_blog_exports_blog_created}). */
    public boolean existsActiveSince(Long blogId, Instant since) {
        return queryFactory.selectOne().from(blogExport)
                .where(blogExport.blog.id.eq(blogId), blogExport.createdAt.goe(since),
                        blogExport.status.ne(ExportStatus.FAILED))
                .fetchFirst() != null;
    }

    /** 블로그의 최근 백업 10건, 새것부터. */
    public List<BlogExport> findRecent(Long blogId) {
        return queryFactory.selectFrom(blogExport)
                .where(blogExport.blog.id.eq(blogId))
                .orderBy(blogExport.createdAt.desc(), blogExport.id.desc())
                .limit(LIST_LIMIT)
                .fetch();
    }

    /** 가장 오래된 PENDING id(없으면 null). */
    public Long findOldestPendingId() {
        return queryFactory.select(blogExport.id).from(blogExport)
                .where(blogExport.status.eq(ExportStatus.PENDING))
                .orderBy(blogExport.createdAt.asc(), blogExport.id.asc())
                .fetchFirst();
    }

    /**
     * 아직 PENDING이면 RUNNING으로 바꾸고 쓸 파일 경로를 적는다(끊겼을 때 기동 정리가 부분 파일을 지울 수 있게). 바뀐 행 수(1 또는 0).
     */
    public long claim(Long exportId, String filePath, Instant now) {
        return queryFactory.update(blogExport)
                .set(blogExport.status, ExportStatus.RUNNING)
                .set(blogExport.filePath, filePath)
                .set(blogExport.updatedAt, now)
                .where(blogExport.id.eq(exportId), blogExport.status.eq(ExportStatus.PENDING))
                .execute();
    }

    /** 만료된 READY(만료 시각 순 최대 {@code limit}건, {@code idx_blog_exports_status_expires}). */
    public List<BlogExport> findExpiredReady(Instant now, int limit) {
        return queryFactory.selectFrom(blogExport)
                .where(blogExport.status.eq(ExportStatus.READY), blogExport.expiresAt.lt(now))
                .orderBy(blogExport.expiresAt.asc(), blogExport.id.asc())
                .limit(limit)
                .fetch();
    }

    /** {@code before} 전에 RUNNING이 된(그 뒤로 바뀌지 않은) 행. 기동 때 끊긴 작업 정리용. */
    public List<BlogExport> findStaleRunning(Instant before) {
        return queryFactory.selectFrom(blogExport)
                .where(blogExport.status.eq(ExportStatus.RUNNING), blogExport.updatedAt.lt(before))
                .orderBy(blogExport.id.asc())
                .fetch();
    }
}
