package net.java21.blog.backend.portal.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.portal.domain.QPortalExclusion.portalExclusion;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.ArrayList;
import java.util.List;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;

import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;

/**
 * 포털 노출 조건의 한 곳(003 FR-088, research P1, 결정 표 1번). 모든 포털 목록(추천, 인기, 최신, 주제 페이지, 인기 태그, 새 블로그,
 * 주제별 글 수)은 이 조각만 쓴다.
 * <pre>
 * portalVisible(c) = PostExposure.bodyVisible()                       // 001 "본문 노출 가능"(보호 글 제외)
 *                    AND blog.portalEnabled                             // FR-089
 *                    AND NOT EXISTS (portal_exclusions WHERE post_id)   // FR-093
 *                    AND user.createdAt <= c.now - c.newMemberDelay     // 가입 후 대기
 *                    AND CHAR_LENGTH(post.contentText) >= c.minContentLength
 * </pre>
 * 쿼리는 {@link PostExposure}처럼 {@code from(post).join(post.blog, blog).join(blog.user, user)}로 시작해야 한다.
 * 이미 읽은 글 한 편은 같은 규칙의 {@link #evaluate}로 판단한다(두 구현이 같은지는 {@code PortalExposureRepositoryTest}가 확인).
 */
public final class PortalExposure {

    private PortalExposure() {
    }

    public static BooleanExpression portalVisible(PortalCriteria c) {
        return PostExposure.bodyVisible()
                .and(blog.portalEnabled.isTrue())
                .and(JPAExpressions.selectOne()
                        .from(portalExclusion)
                        .where(portalExclusion.post.id.eq(post.id))
                        .notExists())
                .and(user.createdAt.loe(c.joinedBefore()))
                .and(post.contentText.length().goe(c.minContentLength()));
    }

    /**
     * 이미 읽은 글(블로그·작성자 포함)이 포털 노출 조건을 만족하지 않는 이유. 빈 목록이면 포털에 나올 수 있다.
     *
     * @param excluded 포털 제외 행이 있는지
     */
    public static List<PortalIneligibility> evaluate(Post p, PortalCriteria c, boolean excluded) {
        List<PortalIneligibility> reasons = new ArrayList<>();
        if (!PostExposure.isBodyVisible(p)) {
            reasons.add(PortalIneligibility.NOT_BODY_VISIBLE);
        }
        if (!p.getBlog().isPortalEnabled()) {
            reasons.add(PortalIneligibility.BLOG_PORTAL_DISABLED);
        }
        if (excluded) {
            reasons.add(PortalIneligibility.EXCLUDED);
        }
        if (p.getBlog().getUser().getCreatedAt() == null || p.getBlog().getUser().getCreatedAt().isAfter(c.joinedBefore())) {
            reasons.add(PortalIneligibility.NEW_MEMBER);
        }
        if (textLength(p.getContentText()) < c.minContentLength()) {
            reasons.add(PortalIneligibility.TOO_SHORT);
        }
        return List.copyOf(reasons);
    }

    /** 본문 텍스트 문자 수(MySQL {@code CHAR_LENGTH}와 같이 코드 포인트 단위). 없으면 -1(어떤 기준도 만족하지 않음). */
    static int textLength(String text) {
        return text == null ? -1 : text.codePointCount(0, text.length());
    }
}
