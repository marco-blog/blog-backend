package net.java21.blog.backend.portal.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.portal.domain.QPortalCuration.portalCuration;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.tag.domain.QPostTag.postTag;
import static net.java21.blog.backend.tag.domain.QTag.tag;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.media.domain.QMedia;
import net.java21.blog.backend.portal.service.PortalCriteria;
import org.springframework.stereotype.Repository;

/**
 * 포털 메인 영역 조회(003 FR-087·091·092, research P6): 인기 태그, 새로 시작한 블로그, 지금 기간 안 추천. 모두 {@link PortalExposure}를
 * 쓰며 각각 쿼리 1회다.
 */
@Repository
public class PortalSectionQueryRepository {

    private static final QMedia ownerMedia = new QMedia("ownerMedia");
    private static final QMedia coverMedia = new QMedia("coverMedia");

    private final JPAQueryFactory queryFactory;

    public PortalSectionQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** {@code since} 이후 발행된 포털 노출 글의 태그 사용 수 상위 {@code limit}개(수 내림차순, 같으면 이름순). */
    public List<PopularTagRow> findPopularTags(PortalCriteria criteria, Instant since, int limit) {
        return queryFactory
                .select(Projections.constructor(PopularTagRow.class, tag.name, postTag.count()))
                .from(postTag)
                .join(postTag.post, post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .join(postTag.tag, tag)
                .where(PortalExposure.portalVisible(criteria), post.publishedAt.goe(since))
                .groupBy(tag.id, tag.name)
                .orderBy(postTag.count().desc(), tag.name.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * 첫 발행이 {@code since} 이후이고 포털 노출 글이 1편 이상인 블로그(블로그·주인 ACTIVE, 포털 켜짐은 노출 조각에 들어 있다),
     * 첫 발행 최신순 {@code limit}개.
     */
    public List<NewBlogRow> findNewBlogs(PortalCriteria criteria, Instant since, int limit) {
        return queryFactory
                .select(Projections.constructor(NewBlogRow.class, blog.handle, blog.title, blog.description,
                        coverMedia.mediaKey, user.nickname, ownerMedia.mediaKey, blog.firstPublishedAt))
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .leftJoin(user.profileMedia, ownerMedia)
                .leftJoin(blog.coverMedia, coverMedia)
                .where(PortalExposure.portalVisible(criteria), blog.firstPublishedAt.goe(since))
                .groupBy(blog.id, blog.handle, blog.title, blog.description, coverMedia.mediaKey, user.nickname,
                        ownerMedia.mediaKey, blog.firstPublishedAt)
                .orderBy(blog.firstPublishedAt.desc(), blog.id.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * {@code now}가 노출 기간 안(시작 포함·종료 제외)이고 글이 포털 노출 조건을 만족하는 추천의 글 id({@code sort_order}, id 순,
     * 같은 글은 한 번). 조건을 잃은 추천은 빠진다(FR-092).
     */
    public List<Long> findActiveCurationPostIds(PortalCriteria criteria, Instant now, int limit) {
        List<Long> ids = queryFactory
                .select(post.id)
                .from(portalCuration)
                .join(portalCuration.post, post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(portalCuration.startsAt.loe(now), portalCuration.endsAt.gt(now),
                        PortalExposure.portalVisible(criteria))
                .orderBy(portalCuration.sortOrder.asc(), portalCuration.id.asc())
                .limit(limit)
                .fetch();
        return List.copyOf(new LinkedHashSet<>(ids));
    }
}
