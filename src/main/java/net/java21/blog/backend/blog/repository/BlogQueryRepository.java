package net.java21.blog.backend.blog.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;

import java.time.Instant;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.dto.BlogLink;
import net.java21.blog.backend.post.domain.PostStatus;
import org.springframework.stereotype.Repository;

/** 블로그 목록·집계 조회(QueryDSL). 엔티티 대신 DTO projection으로 읽어 N+1이 없다. */
@Repository
public class BlogQueryRepository {

    private final JPAQueryFactory queryFactory;

    public BlogQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 내 블로그(삭제 제외)를 만든 순으로, 블로그마다 발행된 글 수(공개·비공개, 임시저장·휴지통 제외)와 함께 읽는다.
     * 블로그 수와 관계없이 쿼리 1회(LEFT JOIN + GROUP BY).
     */
    public List<MyBlogRow> findMyBlogs(Long userId) {
        return queryFactory
                .select(Projections.constructor(MyBlogRow.class,
                        blog.id, blog.handle, blog.title, blog.coverMediaId, post.id.count(), blog.createdAt))
                .from(blog)
                .leftJoin(post).on(post.blog.eq(blog).and(post.status.eq(PostStatus.PUBLISHED)))
                .where(blog.user.id.eq(userId), blog.status.eq(BlogStatus.ACTIVE))
                .groupBy(blog.id, blog.handle, blog.title, blog.coverMediaId, blog.createdAt)
                .orderBy(blog.id.asc())
                .fetch();
    }

    /** 내 블로그(삭제 제외) 바로가기, 만든 순. 쿼리 1회. */
    public List<BlogLink> findActiveBlogLinks(Long userId) {
        return queryFactory
                .select(Projections.constructor(BlogLink.class, blog.handle, blog.title))
                .from(blog)
                .where(blog.user.id.eq(userId), blog.status.eq(BlogStatus.ACTIVE))
                .orderBy(blog.id.asc())
                .fetch();
    }

    /**
     * 블로그 삭제 때 그 블로그의 휴지통에 없는 글을 모두 휴지통으로 옮긴다(FR-159, data-model blogs).
     * 직전 상태를 {@code status_before_delete}에 남긴다. UPDATE 1회.
     *
     * @return 옮긴 글 수
     */
    public long moveAllPostsToTrash(Long blogId, Instant now) {
        return queryFactory.update(post)
                .set(post.statusBeforeDelete, post.status)
                .set(post.status, PostStatus.DELETED)
                .set(post.deletedAt, now)
                .where(post.blog.id.eq(blogId), post.status.ne(PostStatus.DELETED))
                .execute();
    }
}
