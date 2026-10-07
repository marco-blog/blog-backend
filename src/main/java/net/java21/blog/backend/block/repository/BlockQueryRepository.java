package net.java21.blog.backend.block.repository;

import static net.java21.blog.backend.block.domain.QBlogBlock.blogBlock;
import static net.java21.blog.backend.media.domain.QMedia.media;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** 블로그 차단 목록(004 FR-146). 회원 닉네임·프로필 이미지 키를 같은 쿼리에서 읽는다(목록 1회 + 전체 수 1회). */
@Repository
public class BlockQueryRepository {

    private final JPAQueryFactory queryFactory;

    public BlockQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 목록 한 줄(DTO projection). */
    public record BlockRow(Long userId, String nickname, String profileMediaKey, Instant blockedAt) {
    }

    /** 차단 최신순 페이지. */
    public Page<BlockRow> findPage(Long blogId, Pageable pageable) {
        List<BlockRow> rows = queryFactory
                .select(Projections.constructor(BlockRow.class, user.id, user.nickname, media.mediaKey,
                        blogBlock.createdAt))
                .from(blogBlock)
                .join(blogBlock.blockedUser, user)
                .leftJoin(user.profileMedia, media)
                .where(blogBlock.id.blogId.eq(blogId))
                .orderBy(blogBlock.createdAt.desc(), blogBlock.id.blockedUserId.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(blogBlock.count()).from(blogBlock)
                .where(blogBlock.id.blogId.eq(blogId)).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }
}
