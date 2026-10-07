package net.java21.blog.backend.admin.content;

import static net.java21.blog.backend.post.domain.QPost.post;

import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.domain.PostStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** 관리자가 숨긴 글 id(숨긴 순서 최신 — 숨김이 마지막 수정이므로 {@code updated_at} 내림차순). 쿼리 2회(목록, 개수). */
@Repository
public class HiddenPostQueryRepository {

    private final JPAQueryFactory queryFactory;

    public HiddenPostQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<Long> findHiddenPostIds(Pageable pageable) {
        List<Long> ids = queryFactory.select(post.id).from(post)
                .where(post.status.eq(PostStatus.HIDDEN))
                .orderBy(post.updatedAt.desc(), post.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(post.count()).from(post).where(post.status.eq(PostStatus.HIDDEN)).fetchOne();
        return new PageImpl<>(ids, pageable, total == null ? 0 : total);
    }
}
