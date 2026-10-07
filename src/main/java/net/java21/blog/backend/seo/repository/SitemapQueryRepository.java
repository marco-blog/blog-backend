package net.java21.blog.backend.seo.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.releasenote.domain.QReleaseNote.releaseNote;
import static net.java21.blog.backend.topic.domain.QTopic.topic;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.List;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;
import net.java21.blog.backend.topic.domain.QTopic;
import org.springframework.stereotype.Repository;

/**
 * 사이트맵 조회(002 FR-037, research D5). 본문 노출 가능 글({@link PostExposure#bodyVisible()})만 쓰며, 서버 캐시 없이 요청마다 읽는다
 * (비공개로 바뀐 글이 바로 빠지게, SC-004). 각 메서드는 쿼리 1회이고 DTO projection이다.
 */
@Repository
public class SitemapQueryRepository {

    private static final QTopic parentTopic = new QTopic("parentTopic");

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

    /**
     * 운영자 숨김이 아닌 주제 페이지 경로(003 FR-094, 자동 숨김 포함): 대분류 {@code /topics/{major}}, 소분류
     * {@code /topics/{major}/{minor}}. 숨긴 대분류의 소분류는 빠진다. 순서는 대분류 순서 → 그 소분류 순서.
     */
    public List<String> findTopicPaths() {
        return queryFactory
                .select(topic.slug, parentTopic.slug)
                .from(topic)
                .leftJoin(topic.parent, parentTopic)
                .where(topic.adminHidden.isFalse(), parentTopic.id.isNull().or(parentTopic.adminHidden.isFalse()))
                .orderBy(parentTopic.sortOrder.coalesce(topic.sortOrder).asc(),
                        parentTopic.id.coalesce(topic.id).asc(),
                        Expressions.numberTemplate(Integer.class, "case when {0} is null then 0 else 1 end",
                                parentTopic.id).asc(),
                        topic.sortOrder.asc(), topic.id.asc())
                .fetch()
                .stream()
                .map(row -> {
                    String parentSlug = row.get(parentTopic.slug);
                    String slug = row.get(topic.slug);
                    return parentSlug == null ? "/topics/" + slug : "/topics/" + parentSlug + "/" + slug;
                })
                .toList();
    }

    /** 게시된 릴리스 노트의 버전과 수정 시각(003 FR-164), 버전 내림차순. 초안은 없다. */
    public List<SitemapReleaseNoteRow> findReleaseNotes() {
        return queryFactory
                .select(Projections.constructor(SitemapReleaseNoteRow.class, releaseNote.version,
                        releaseNote.updatedAt))
                .from(releaseNote)
                .where(releaseNote.status.eq(ReleaseNoteStatus.PUBLISHED))
                .orderBy(releaseNote.versionMajor.desc(), releaseNote.versionMinor.desc(),
                        releaseNote.versionPatch.desc())
                .fetch();
    }
}
