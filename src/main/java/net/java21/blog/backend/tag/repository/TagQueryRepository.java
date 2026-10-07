package net.java21.blog.backend.tag.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.tag.domain.QPostTag.postTag;
import static net.java21.blog.backend.tag.domain.QTag.tag;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.tag.dto.BlogTagResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 태그 조회(T177·T179, QueryDSL, FR-025·026). 노출 조건은 {@link PostExposure}만 쓰고 결과는 DTO projection으로 읽는다.
 * 글 목록의 {@code tags}는 {@link #findTagNames}로 목록의 글 id를 한 번에 읽어 채운다(글 수와 무관하게 쿼리 1회).
 */
@Repository
public class TagQueryRepository {

    private final JPAQueryFactory queryFactory;

    public TagQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 글 id별 태그 이름(이름순). 태그가 없는 글은 결과에 없다. 쿼리 1회(IN), 빈 목록이면 0회.
     */
    public Map<Long, List<String>> findTagNames(Collection<Long> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        List<Tuple> tuples = queryFactory
                .select(postTag.post.id, tag.name)
                .from(postTag)
                .join(postTag.tag, tag)
                .where(postTag.post.id.in(postIds))
                .orderBy(postTag.post.id.asc(), tag.name.asc())
                .fetch();
        Map<Long, List<String>> names = new LinkedHashMap<>();
        for (Tuple tuple : tuples) {
            names.computeIfAbsent(tuple.get(postTag.post.id), k -> new ArrayList<>()).add(tuple.get(tag.name));
        }
        return names;
    }

    /** 블로그 태그 목록: 목록 노출 가능 글에 달린 태그와 글 수(많은 순, 같으면 이름순). 쿼리 1회. */
    public List<BlogTagResponse> findBlogTags(Long blogId) {
        return findBlogTags(blogId, Integer.MAX_VALUE);
    }

    /** 블로그 태그 상위 {@code limit}개(004 사이드바 TAGS는 30개). 쿼리 1회. */
    public List<BlogTagResponse> findBlogTags(Long blogId, int limit) {
        NumberExpression<Long> count = postTag.post.id.count();
        return queryFactory
                .select(Projections.constructor(BlogTagResponse.class, tag.name, count))
                .from(postTag)
                .join(postTag.tag, tag)
                .join(postTag.post, post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(blog.id.eq(blogId), PostExposure.listable())
                .groupBy(tag.name)
                .orderBy(count.desc(), tag.name.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * 서비스 전체 태그별 글(목록 노출 가능만, 발행 최신순, 블로그 주소 포함). 쿼리 2회(목록, 전체 수).
     *
     * @param tagName 정규화한 태그 이름
     */
    public Page<TaggedPostRow> findTaggedPosts(String tagName, Pageable pageable) {
        List<TaggedPostRow> rows = queryFactory
                .select(Projections.constructor(TaggedPostRow.class,
                        post.id, post.title, post.summary, post.thumbnailUrl, category.id, category.name,
                        post.viewCount, post.commentCount, post.visibility, post.status, post.publishedAt,
                        post.updatedAt, blog.handle, post.notice))
                .from(postTag)
                .join(postTag.tag, tag)
                .join(postTag.post, post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .leftJoin(post.category, category)
                .where(tag.name.eq(tagName), PostExposure.listable())
                .orderBy(post.publishedAt.desc(), post.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory
                .select(postTag.post.id.count())
                .from(postTag)
                .join(postTag.tag, tag)
                .join(postTag.post, post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(tag.name.eq(tagName), PostExposure.listable())
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }
}
