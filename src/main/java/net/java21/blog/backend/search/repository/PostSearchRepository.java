package net.java21.blog.backend.search.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
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
 *   <li>목록 노출 가능이지만 본문 노출 가능이 아님(004 보호 글) AND {@code MATCH(title)} — 그런 공개 범위가 없는 002에서는
 *       조건을 넣지 않는다({@link PostExposure#hasListableWithoutBody()})</li>
 * </ol>
 * 발행 최신순(같은 시각은 id 내림차순), DTO projection으로 쿼리 2회(목록, 전체 수). MySQL 전용이라 H2에서는 실행하지 않는다.
 */
@Repository
public class PostSearchRepository {

    /**
     * 제목+본문 또는 태그가 일치하는 글 id. FULLTEXT 일치는 OR로 묶으면 MySQL이 인덱스를 쓰지 못하고 행마다 계산해(10만 편에서
     * 수 초~수십 초) 각 일치를 따로 찾아 {@code UNION}하고, 파생 테이블로 감싸 한 번만 계산(구체화)하게 한다.
     */
    private static final String BODY_MATCHES = "{0} in (select m.id from ("
            + "select p2.id as id from Post p2 where " + MySqlFullTextFunctions.MATCH_TITLE_CONTENT
            + "(p2.title, p2.contentText, {1}) > 0 "
            + "union select pt.id.postId as id from PostTag pt join pt.tag t where " + MySqlFullTextFunctions.MATCH_TAG
            + "(t.name, {1}) > 0) m)";

    /** 제목만 일치하는 글 id(004 보호 글은 제목으로만 찾는다). */
    private static final String TITLE_MATCHES = "{0} in (select m.id from ("
            + "select p3.id as id from Post p3 where " + MySqlFullTextFunctions.MATCH_TITLE + "(p3.title, {1}) > 0) m)";

    private final JPAQueryFactory queryFactory;

    public PostSearchRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<SearchPostRow> search(SearchQuery query, Pageable pageable) {
        return search(query, null, pageable);
    }

    /** 004 블로그 내 검색(FR-061): {@code blogId}가 있으면 그 블로그 글만(본문·제목 검색 조건 모두에 적용). */
    public Page<SearchPostRow> search(SearchQuery query, Long blogId, Pageable pageable) {
        BooleanExpression where = matches(query.booleanQuery());
        if (blogId != null) {
            where = blog.id.eq(blogId).and(where);
        }
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
        BooleanExpression where = PostExposure.bodyVisible().and(Expressions.booleanTemplate(BODY_MATCHES, post.id,
                Expressions.constant(booleanQuery)));
        if (PostExposure.hasListableWithoutBody()) {
            where = where.or(PostExposure.listable().and(post.visibility.ne(PostVisibility.PUBLIC))
                    .and(Expressions.booleanTemplate(TITLE_MATCHES, post.id, Expressions.constant(booleanQuery))));
        }
        return where;
    }
}
