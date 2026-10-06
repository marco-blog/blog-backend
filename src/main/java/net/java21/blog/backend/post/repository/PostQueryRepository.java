package net.java21.blog.backend.post.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.post.domain.QPostDraft.postDraft;
import static net.java21.blog.backend.tag.domain.QPostTag.postTag;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.dto.LatestDraftResponse;
import net.java21.blog.backend.post.dto.PostListFilter;
import net.java21.blog.backend.post.dto.PostLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 글 목록·이전/다음·최근 임시저장 조회(T099, QueryDSL). 노출 조건은 {@link PostExposure}만 쓰고,
 * 결과는 DTO projection으로 읽어 행 수와 관계없이 쿼리 수가 고정이다.
 */
@Repository
public class PostQueryRepository {

    private final JPAQueryFactory queryFactory;

    public PostQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 블로그 글 목록(홈, 조건 없음). {@link #findListablePosts(Long, PostListFilter, Pageable)} 참고. */
    public Page<PostSummaryRow> findListablePosts(Long blogId, Pageable pageable) {
        return findListablePosts(blogId, PostListFilter.NONE, pageable);
    }

    /**
     * 블로그 글 목록(홈·카테고리별·태그별): 목록 노출 가능 글만, 발행 최신순(FR-011, FR-018, FR-026). 쿼리 2회(목록, 전체 수).
     * 주인이 요청해도 같다(주인의 전체 목록은 블로그 관리 화면). 카테고리 조건은 상위 카테고리면 하위 카테고리 글을 포함하고
     * (tasks.md 결정 4), 태그 조건은 정규화한 이름이 같은 태그가 달린 글이다. 카테고리는 LEFT JOIN으로 함께 읽는다.
     */
    public Page<PostSummaryRow> findListablePosts(Long blogId, PostListFilter filter, Pageable pageable) {
        BooleanExpression where = blog.id.eq(blogId).and(PostExposure.listable());
        if (filter.categoryId() != null) {
            where = where.and(category.id.eq(filter.categoryId()).or(category.parent.id.eq(filter.categoryId())));
        }
        if (filter.tag() != null) {
            where = where.and(JPAExpressions.selectOne()
                    .from(postTag)
                    .where(postTag.post.id.eq(post.id), postTag.tag.name.eq(filter.tag()))
                    .exists());
        }
        List<PostSummaryRow> rows = queryFactory
                .select(Projections.constructor(PostSummaryRow.class,
                        post.id, post.title, post.summary, post.thumbnailUrl, category.id, category.name,
                        post.viewCount, post.commentCount, post.visibility, post.status, post.publishedAt,
                        post.updatedAt))
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
                .leftJoin(post.category, category)
                .where(where)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 같은 블로그에서 바로 앞(더 먼저 발행된) 목록 노출 가능 글. 쿼리 1회. */
    public Optional<PostLink> findPrevious(Long blogId, Long postId, Instant publishedAt) {
        return Optional.ofNullable(queryFactory
                .select(Projections.constructor(PostLink.class, post.id, post.title))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(blog.id.eq(blogId), PostExposure.listable(),
                        post.publishedAt.lt(publishedAt)
                                .or(post.publishedAt.eq(publishedAt).and(post.id.lt(postId))))
                .orderBy(post.publishedAt.desc(), post.id.desc())
                .fetchFirst());
    }

    /** 같은 블로그에서 바로 뒤(더 나중에 발행된) 목록 노출 가능 글. 쿼리 1회. */
    public Optional<PostLink> findNext(Long blogId, Long postId, Instant publishedAt) {
        return Optional.ofNullable(queryFactory
                .select(Projections.constructor(PostLink.class, post.id, post.title))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(blog.id.eq(blogId), PostExposure.listable(),
                        post.publishedAt.gt(publishedAt)
                                .or(post.publishedAt.eq(publishedAt).and(post.id.gt(postId))))
                .orderBy(post.publishedAt.asc(), post.id.asc())
                .fetchFirst());
    }

    /** 블로그의 가장 최근 작성 중 사본(휴지통 글 제외, 이어 쓰기 확인용). 쿼리 1회. */
    public Optional<LatestDraftResponse> findLatestDraft(Long blogId) {
        return Optional.ofNullable(queryFactory
                .select(Projections.constructor(LatestDraftResponse.class, post.id, postDraft.title, postDraft.savedAt))
                .from(postDraft)
                .join(postDraft.post, post)
                .where(post.blog.id.eq(blogId), post.status.ne(PostStatus.DELETED))
                .orderBy(postDraft.savedAt.desc(), post.id.desc())
                .fetchFirst());
    }
}
