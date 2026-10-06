package net.java21.blog.backend.post.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.comment.domain.QComment.comment;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.post.domain.QPostDraft.postDraft;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.subscription.repository.BlogSubscriptionRepository;
import org.springframework.stereotype.Repository;

/**
 * 휴지통 비우기(T103, FR-084·159, research R26)의 조회·삭제. 건수 단위로 나눠 부른다.
 * 글의 {@code post_drafts}·{@code post_tags}·{@code post_media}는 DB의 {@code ON DELETE CASCADE}로 함께 지워진다
 * (작성 중 사본은 엔티티가 있으므로 먼저 명시적으로 지운다). {@code ON DELETE CASCADE}가 없는 FK(댓글, 트랙백, 포털 추천·제외)는
 * 글보다 먼저 지운다(T196·T189): 댓글은 답글 → 댓글 순, 트랙백(005)·포털(003)은 엔티티가 없으므로 SQL로.
 * 002: 좋아요({@code post_likes})는 {@code ON DELETE CASCADE}로 글과 함께 지워지고, 블로그 구독({@code blog_subscriptions})은
 * CASCADE가 없으므로 블로그를 비울 때 먼저 지운다.
 */
@Repository
public class TrashPurgeRepository {

    private final JPAQueryFactory queryFactory;
    private final EntityManager em;
    private final BlogSubscriptionRepository subscriptionRepository;

    public TrashPurgeRepository(JPAQueryFactory queryFactory, EntityManager em,
            BlogSubscriptionRepository subscriptionRepository) {
        this.queryFactory = queryFactory;
        this.em = em;
        this.subscriptionRepository = subscriptionRepository;
    }

    /** {@code deleted_at < cutoff}인 휴지통 글 id(오래된 순, 최대 {@code limit}개). */
    public List<Long> findPurgeablePostIds(Instant cutoff, int limit) {
        return queryFactory.select(post.id)
                .from(post)
                .where(post.status.eq(PostStatus.DELETED), post.deletedAt.lt(cutoff))
                .orderBy(post.deletedAt.asc(), post.id.asc())
                .limit(limit)
                .fetch();
    }

    /** 글을 영구 삭제한다. 다시 확인해 휴지통 글만 지운다. */
    public long deletePosts(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        List<Long> purgeable = queryFactory.select(post.id).from(post)
                .where(post.id.in(ids), post.status.eq(PostStatus.DELETED))
                .fetch();
        if (purgeable.isEmpty()) {
            return 0;
        }
        deleteRowsReferencing(purgeable);
        queryFactory.delete(postDraft).where(postDraft.post.id.in(purgeable)).execute();
        long deleted = queryFactory.delete(post)
                .where(post.id.in(purgeable), post.status.eq(PostStatus.DELETED))
                .execute();
        em.clear();
        return deleted;
    }

    /** 영구 삭제할 글을 가리키는 행 중 {@code ON DELETE CASCADE}가 없는 것을 지운다. */
    private void deleteRowsReferencing(List<Long> postIds) {
        queryFactory.delete(comment).where(comment.post.id.in(postIds), comment.parent.isNotNull()).execute();
        queryFactory.delete(comment).where(comment.post.id.in(postIds)).execute();
        em.createNativeQuery("DELETE FROM trackbacks WHERE post_id IN (:ids)").setParameter("ids", postIds)
                .executeUpdate();
        // 서비스 안의 다른 글이 보낸 트랙백은 남기고 보낸 글 연결만 끊는다.
        em.createNativeQuery("UPDATE trackbacks SET source_post_id = NULL WHERE source_post_id IN (:ids)")
                .setParameter("ids", postIds).executeUpdate();
        em.createNativeQuery("DELETE FROM portal_curations WHERE post_id IN (:ids)").setParameter("ids", postIds)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM portal_exclusions WHERE post_id IN (:ids)").setParameter("ids", postIds)
                .executeUpdate();
    }

    /** 삭제 후 보관 기간이 지났고 아직 비우지 않은(title이 남은) 블로그 id(최대 {@code limit}개). */
    public List<Long> findPurgeableBlogIds(Instant cutoff, int limit) {
        return queryFactory.select(blog.id)
                .from(blog)
                .where(blog.status.eq(BlogStatus.DELETED), blog.deletedAt.lt(cutoff), blog.title.ne(""))
                .orderBy(blog.deletedAt.asc(), blog.id.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * 삭제된 블로그를 비운다(FR-159, data-model blogs, T181): 아직 남은 글의 카테고리를 먼저 비우고(외래 키) 카테고리를
     * 하위 → 상위 순으로 지운 뒤, 주소 재사용을 막기 위해 {@code blogs} 행은 남기되 제목·소개·대표 이미지 참조를 비운다.
     * 그 블로그들의 구독 행(002)을 먼저 지운다. 모두 집합 UPDATE·DELETE다.
     */
    public long purgeBlogs(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        subscriptionRepository.deleteByBlogIds(ids);
        queryFactory.update(post).setNull(post.category).where(post.blog.id.in(ids)).execute();
        queryFactory.delete(category).where(category.blog.id.in(ids), category.parent.isNotNull()).execute();
        queryFactory.delete(category).where(category.blog.id.in(ids)).execute();
        long purged = queryFactory.update(blog)
                .set(blog.title, "")
                .setNull(blog.description)
                .setNull(blog.coverMedia)
                .where(blog.id.in(ids))
                .execute();
        em.clear();
        return purged;
    }
}
