package net.java21.blog.backend.search.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.tag.domain.QPostTag.postTag;
import static net.java21.blog.backend.tag.domain.QTag.tag;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.common.persistence.MySqlFullTextFunctions;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.search.service.SearchQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 서비스 전체 글 검색(002 FR-035, research D4). MySQL FULLTEXT ngram 인덱스를 {@link MySqlFullTextFunctions}로 쓰고
 * 노출 조건은 {@link PostExposure}만 쓴다. 한 글은 아래 중 하나면 결과에 든다.
 * <ol>
 *   <li>본문 노출 가능 AND {@code MATCH(title, content_text)}</li>
 *   <li>본문 노출 가능 AND 글의 태그 중 {@code MATCH(tags.name)}인 것이 있음</li>
 *   <li>목록 노출 가능이지만 본문 노출 가능이 아님(004 보호 글) AND {@code MATCH(title)} — 002 시점에는 결과가 없다</li>
 * </ol>
 * 발행 최신순(같은 시각은 id 내림차순), DTO projection으로 쿼리 2회(목록, 전체 수). MySQL 전용이라 H2에서는 실행하지 않는다.
 */
@Repository
public class PostSearchRepository {

    private final JPAQueryFactory queryFactory;

    public PostSearchRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<SearchPostRow> search(SearchQuery query, Pageable pageable) {
        BooleanExpression where = matches(query.booleanQuery());
        List<SearchPostRow> rows = queryFactory
                .select(Projections.constructor(SearchPostRow.class,
                        post.id, post.title, post.summary, post.thumbnailUrl, category.id, category.name,
                        post.viewCount, post.commentCount, post.visibility, post.status, post.publishedAt,
                        post.updatedAt, blog.handle, blog.title))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .leftJoin(post.category, category)
                .where(where)
                .orderBy(post.publishedAt.desc(), post.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory
                .select(post.count())
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(where)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private static BooleanExpression matches(String booleanQuery) {
        BooleanExpression titleOrBody = MySqlFullTextFunctions
                .matchTitleContent(post.title, post.contentText, booleanQuery).gt(0.0);
        BooleanExpression tagged = JPAExpressions.selectOne()
                .from(postTag)
                .join(postTag.tag, tag)
                .where(postTag.post.id.eq(post.id), MySqlFullTextFunctions.matchTag(tag.name, booleanQuery).gt(0.0))
                .exists();
        BooleanExpression titleOnly = MySqlFullTextFunctions.matchTitle(post.title, booleanQuery).gt(0.0);
        return PostExposure.bodyVisible().and(titleOrBody.or(tagged))
                .or(PostExposure.listable().and(post.visibility.ne(PostVisibility.PUBLIC)).and(titleOnly));
    }
}
