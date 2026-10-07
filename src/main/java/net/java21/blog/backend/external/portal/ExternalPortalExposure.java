package net.java21.blog.backend.external.portal;

import static net.java21.blog.backend.portal.domain.QPortalExclusion.portalExclusion;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;

import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.QExternalBlog;
import net.java21.blog.backend.external.domain.QExternalPost;
import net.java21.blog.backend.topic.domain.QTopic;

/**
 * 외부 글의 포털 노출 조건(007 FR-123, research E13). 쿼리는 {@link #join(JPAQuery)}의 조인(글 + 블로그 + 주제 + 상위 주제)을 쓰고
 * {@link #visible(Instant)}로 거른다.
 * <ul>
 *   <li>글 ACTIVE</li>
 *   <li>블로그 ACTIVE·PAUSED·STOPPED·RELEASED(일시 중지·자동 중지는 이미 수집된 글을 그대로 두고, RELEASED는 "글 남기기"로 해제한
 *       경우다. 삭제를 골랐으면 글이 없고 탈퇴로 해제된 글은 REMOVED). PENDING·REJECTED·BLOCKED는 안 보임</li>
 *   <li>포털 제외({@code portal_exclusions.external_post_id}) 없음</li>
 *   <li>발행 시각이 지금 이전(포함)</li>
 *   <li>주제(소분류)와 그 대분류가 운영자 숨김이 아님</li>
 * </ul>
 * 003의 가입 24시간·본문 최소 길이·블로그 포털 노출 설정은 적용하지 않는다(FR-123).
 */
public final class ExternalPortalExposure {

    public static final QExternalPost ep = new QExternalPost("ep");
    public static final QExternalBlog eb = new QExternalBlog("eb");
    public static final QTopic topic = new QTopic("extTopic");
    public static final QTopic parentTopic = new QTopic("extParentTopic");

    /** 글이 포털에 보일 수 있는 블로그 상태. */
    public static final Set<ExternalBlogStatus> VISIBLE_BLOG_STATUSES = EnumSet.of(ExternalBlogStatus.ACTIVE,
            ExternalBlogStatus.PAUSED, ExternalBlogStatus.STOPPED, ExternalBlogStatus.RELEASED);

    private ExternalPortalExposure() {
    }

    /** {@code from external_posts ep join external_blogs eb join topics t left join topics parent}. */
    public static <T> JPAQuery<T> join(JPAQuery<T> query) {
        return query.from(ep)
                .join(ep.externalBlog, eb)
                .join(ep.topic, topic)
                .leftJoin(topic.parent, parentTopic);
    }

    public static BooleanExpression visible(Instant now) {
        return ep.status.eq(ExternalPostStatus.ACTIVE)
                .and(eb.status.in(VISIBLE_BLOG_STATUSES))
                .and(JPAExpressions.selectOne()
                        .from(portalExclusion)
                        .where(portalExclusion.externalPost.id.eq(ep.id))
                        .notExists())
                .and(ep.publishedAt.loe(now))
                .and(topic.adminHidden.isFalse())
                .and(parentTopic.id.isNull().or(parentTopic.adminHidden.isFalse()));
    }
}
