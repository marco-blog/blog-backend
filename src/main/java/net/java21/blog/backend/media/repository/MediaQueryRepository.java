package net.java21.blog.backend.media.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.media.domain.QMedia.media;
import static net.java21.blog.backend.media.domain.QPostMedia.postMedia;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaStatus;
import org.springframework.stereotype.Repository;

/**
 * 이미지 조회·정리(QueryDSL). 모든 메서드는 다루는 이미지 수와 관계없이 쿼리 1회다.
 */
@Repository
public class MediaQueryRepository {

    private final JPAQueryFactory queryFactory;

    public MediaQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 이미지 제공용 값. */
    public Optional<MediaFileRow> findFile(String mediaKey) {
        return Optional.ofNullable(queryFactory
                .select(Projections.constructor(MediaFileRow.class, media.id, media.mediaKey, media.owner.id,
                        media.status, media.storedPath, media.mime, media.width, media.height))
                .from(media)
                .where(media.mediaKey.eq(mediaKey))
                .fetchOne());
    }

    /** 회원의 TEMP 합계(바이트, FR-074). */
    public long sumTempBytes(Long ownerId) {
        Long sum = queryFactory.select(media.sizeBytes.sumLong())
                .from(media)
                .where(media.owner.id.eq(ownerId), media.status.eq(MediaStatus.TEMP))
                .fetchOne();
        return sum == null ? 0 : sum;
    }

    /** 이 회원이 올린 이미지 중 키가 맞는 것(본문·프로필·대표 이미지 연결용). */
    public List<Media> findOwnedByKeys(Long ownerId, Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return queryFactory.selectFrom(media)
                .where(media.owner.id.eq(ownerId), media.mediaKey.in(keys))
                .fetch();
    }

    /**
     * 정리 대상 판단(FR-073): 주어진 ATTACHED 이미지 중 {@code post_media}(발행본·작성 중 사본, 휴지통 글 포함),
     * {@code users.profile_media_id}, {@code blogs.cover_media_id} 어디에서도 참조하지 않는 것을 ORPHANED로. UPDATE 1회.
     *
     * @return ORPHANED로 바꾼 수
     */
    public long markOrphanedIfUnreferenced(Collection<Long> mediaIds) {
        if (mediaIds.isEmpty()) {
            return 0;
        }
        return queryFactory.update(media)
                .set(media.status, MediaStatus.ORPHANED)
                .where(media.id.in(mediaIds),
                        media.status.eq(MediaStatus.ATTACHED),
                        JPAExpressions.selectOne().from(postMedia).where(postMedia.id.mediaId.eq(media.id)).notExists(),
                        JPAExpressions.selectOne().from(user).where(user.profileMedia.id.eq(media.id)).notExists(),
                        JPAExpressions.selectOne().from(blog).where(blog.coverMedia.id.eq(media.id)).notExists())
                .execute();
    }

    /** 블로그들의 대표 이미지 id(없는 것은 빼고). */
    public List<Long> findCoverMediaIds(Collection<Long> blogIds) {
        if (blogIds.isEmpty()) {
            return List.of();
        }
        return queryFactory.select(blog.coverMedia.id)
                .from(blog)
                .where(blog.id.in(blogIds), blog.coverMedia.isNotNull())
                .fetch();
    }

    /** 정리 대상: {@code created_at < tempCutoff}인 TEMP와 ORPHANED(id 순, 최대 {@code limit}개). */
    public List<CleanupCandidate> findCleanupCandidates(Instant tempCutoff, int limit) {
        return queryFactory
                .select(Projections.constructor(CleanupCandidate.class, media.id, media.mediaKey, media.status,
                        media.storedPath))
                .from(media)
                .where(media.status.eq(MediaStatus.TEMP).and(media.createdAt.lt(tempCutoff))
                        .or(media.status.eq(MediaStatus.ORPHANED)))
                .orderBy(media.id.asc())
                .limit(limit)
                .fetch();
    }

    /** 고른 뒤 상태가 바뀌지 않았을 때만 행을 지운다(그 사이 다시 참조됐으면 0). */
    public long deleteIfStill(Long id, MediaStatus status) {
        return queryFactory.delete(media)
                .where(media.id.eq(id), media.status.eq(status))
                .execute();
    }
}
