package net.java21.blog.backend.post.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import org.springframework.stereotype.Repository;

/**
 * 월별 보관함 조회(004 FR-061, research B12). 연·월 묶음은 서비스 시간대 기준이라 DB 함수(시간대 표가 필요한
 * {@code CONVERT_TZ})를 쓰지 않고 목록 노출 가능 글의 발행 시각만 한 번에 읽어 {@code BlogCalendar}로 묶는다. 쿼리 1회.
 */
@Repository
public class ArchiveQueryRepository {

    private final JPAQueryFactory queryFactory;

    public ArchiveQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 블로그의 목록 노출 가능 글 발행 시각, 최신순. */
    public List<Instant> findPublishedTimes(Long blogId) {
        return queryFactory.select(post.publishedAt)
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(blog.id.eq(blogId), PostExposure.listable())
                .orderBy(post.publishedAt.desc())
                .fetch();
    }
}
