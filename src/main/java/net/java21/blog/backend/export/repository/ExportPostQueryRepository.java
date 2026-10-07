package net.java21.blog.backend.export.repository;

import static net.java21.blog.backend.category.domain.QCategory.category;
import static net.java21.blog.backend.media.domain.QMedia.media;
import static net.java21.blog.backend.media.domain.QPostMedia.postMedia;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.post.domain.QPostDraft.postDraft;
import static net.java21.blog.backend.topic.domain.QTopic.topic;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import org.springframework.stereotype.Repository;

/**
 * 백업 zip을 만들 때 읽는 것(004 research B14). 글은 id 순으로 묶음씩(글 + 태그 IN + 사본 IN = 묶음마다 쿼리 3회) 읽어
 * 메모리를 일정하게 두고, 카테고리·주제·이미지는 블로그마다 한 번씩 읽는다. 휴지통(DELETED) 글은 넣지 않는다.
 */
@Repository
public class ExportPostQueryRepository {

    private final JPAQueryFactory queryFactory;

    public ExportPostQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 이미지 한 장: 원본 파일 위치(임시 영역인지)와 확장자를 정할 정보. */
    public record ExportImage(String mediaKey, String storedPath, String storedName, String mime,
            MediaStatus status) {
    }

    /** 블로그의 카테고리 전체(상위 먼저 정렬). 쿼리 1회. */
    public List<Category> findCategories(Long blogId) {
        return queryFactory.selectFrom(category)
                .where(category.blog.id.eq(blogId))
                .orderBy(category.sortOrder.asc(), category.id.asc())
                .fetch();
    }

    /** 주제 id → slug(전체, 수십 개). 쿼리 1회. */
    public Map<Long, String> findTopicSlugs() {
        Map<Long, String> slugs = new LinkedHashMap<>();
        for (Tuple row : queryFactory.select(topic.id, topic.slug).from(topic).fetch()) {
            slugs.put(row.get(topic.id), row.get(topic.slug));
        }
        return slugs;
    }

    /** {@code afterId}보다 큰 id의 휴지통이 아닌 글을 id 순 최대 {@code limit}편. 쿼리 1회. */
    public List<Post> findPostBatch(Long blogId, long afterId, int limit) {
        return queryFactory.selectFrom(post)
                .where(post.blog.id.eq(blogId), post.status.ne(PostStatus.DELETED), post.id.gt(afterId))
                .orderBy(post.id.asc())
                .limit(limit)
                .fetch();
    }

    /** 글들의 작성 중 사본. 쿼리 1회(IN), 빈 목록이면 0회. */
    public Map<Long, PostDraft> findDrafts(Collection<Long> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PostDraft> drafts = new LinkedHashMap<>();
        for (PostDraft draft : queryFactory.selectFrom(postDraft).where(postDraft.postId.in(postIds)).fetch()) {
            drafts.put(draft.getPostId(), draft);
        }
        return drafts;
    }

    /**
     * 휴지통이 아닌 글(발행본·사본)이 참조하는 이미지 중 블로그 주인이 올린 것(다른 회원의 이미지는 넣지 않는다), mediaKey 순.
     * 쿼리 1회.
     */
    public List<ExportImage> findImages(Long blogId, Long ownerId) {
        return queryFactory
                .select(Projections.constructor(ExportImage.class, media.mediaKey, media.storedPath, media.storedName,
                        media.mime, media.status))
                .from(media)
                .where(media.owner.id.eq(ownerId),
                        JPAExpressions.selectOne().from(postMedia, post)
                                .where(postMedia.id.mediaId.eq(media.id), post.id.eq(postMedia.id.postId),
                                        post.blog.id.eq(blogId), post.status.ne(PostStatus.DELETED))
                                .exists())
                .orderBy(media.mediaKey.asc())
                .fetch();
    }
}
