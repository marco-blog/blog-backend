package net.java21.blog.backend.post.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.post.domain.QPostDraft.postDraft;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.dto.LatestDraftResponse;
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

    /**
     * 블로그 글 목록(홈): 목록 노출 가능 글만, 발행 최신순(FR-011, FR-018). 쿼리 2회(목록, 전체 수).
     * 주인이 요청해도 같다(주인의 전체 목록은 블로그 관리 화면).
     */
    public Page<PostSummaryRow> findListablePosts(Long blogId, Pageable pageable) {
        List<PostSummaryRow> rows = queryFactory
                .select(Projections.constructor(PostSummaryRow.class,
                        post.id, post.title, post.summary, post.thumbnailUrl, post.viewCount, post.commentCount,
                        post.visibility, post.status, post.publishedAt, post.updatedAt))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(blog.id.eq(blogId), PostExposure.listable())
                .orderBy(post.publishedAt.desc(), post.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory
                .select(post.count())
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(blog.id.eq(blogId), PostExposure.listable())
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
