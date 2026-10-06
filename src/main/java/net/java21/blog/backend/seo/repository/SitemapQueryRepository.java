package net.java21.blog.backend.seo.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.List;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.repository.PostExposure;
import org.springframework.stereotype.Repository;

/**
 * 사이트맵 조회(002 FR-037, research D5). 본문 노출 가능 글({@link PostExposure#bodyVisible()})만 쓰며, 서버 캐시 없이 요청마다 읽는다
 * (비공개로 바뀐 글이 바로 빠지게, SC-004). 각 메서드는 쿼리 1회이고 DTO projection이다.
 */
@Repository
public class SitemapQueryRepository {

    private final JPAQueryFactory queryFactory;

    public SitemapQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 본문 노출 가능 글 수와 가장 늦은 수정 시각. */
    public SitemapStats stats() {
        Tuple row = queryFactory
                .select(post.count(), post.updatedAt.max())
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(PostExposure.bodyVisible())
                .fetchOne();
        if (row == null) {
            return new SitemapStats(0, null);
        }
        Long count = row.get(post.count());
        return new SitemapStats(count == null ? 0 : count, row.get(post.updatedAt.max()));
    }

    /** id 오름차순 {@code index}번째(0부터) 묶음. */
    public List<SitemapPostRow> findPosts(int index, int size) {
        return queryFactory
                .select(Projections.constructor(SitemapPostRow.class, post.id, blog.handle, post.updatedAt))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(PostExposure.bodyVisible())
                .orderBy(post.id.asc())
                .offset((long) index * size)
                .limit(size)
                .fetch();
    }

    /** 본문 노출 가능 글이 1편 이상인 블로그(id 순)와 그 최근 발행 시각. */
    public List<SitemapBlogRow> findBlogs() {
        return queryFactory
                .select(Projections.constructor(SitemapBlogRow.class, blog.handle, post.publishedAt.max()))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(PostExposure.bodyVisible())
                .groupBy(blog.id, blog.handle)
                .orderBy(blog.id.asc())
                .fetch();
    }
}
