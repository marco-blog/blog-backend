package net.java21.blog.backend.post.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.EnumSet;
import java.util.Set;

import com.querydsl.core.types.dsl.BooleanExpression;

import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.user.domain.UserStatus;

/**
 * 글 노출 조건의 한 곳(T098, FR-018, data-model "노출 조건"·"글 노출 매트릭스").
 * 모든 목록·검색·피드·사이트맵·포털 쿼리는 이 조각만 쓰고, 글 상세의 주인 외 판단도 같은 규칙({@link #isBodyVisible})을 쓴다.
 * <ul>
 *   <li><b>목록 노출 가능</b> {@link #listable()}: {@code status = PUBLISHED AND visibility IN (PUBLIC) AND 작성자 ACTIVE AND 블로그 ACTIVE}.
 *       004가 {@code PROTECTED}를 {@link #LISTABLE_VISIBILITIES}에 더한다(목록에 제목만).</li>
 *   <li><b>본문 노출 가능</b> {@link #bodyVisible()}: 목록 노출 가능 AND {@code visibility = PUBLIC}.</li>
 * </ul>
 * QueryDSL 조각은 기본 별칭 {@code post}·{@code blog}·{@code user}를 쓴다. 쿼리는
 * {@code from(post).join(post.blog, blog).join(blog.user, user)}로 블로그와 작성자를 이어야 한다.
 */
public final class PostExposure {

    /** 목록에 (제목으로라도) 나올 수 있는 공개 범위. 004에서 PROTECTED를 더한다. */
    static final Set<PostVisibility> LISTABLE_VISIBILITIES = EnumSet.of(PostVisibility.PUBLIC);

    private PostExposure() {
    }

    public static BooleanExpression listable() {
        return post.status.eq(PostStatus.PUBLISHED)
                .and(post.visibility.in(LISTABLE_VISIBILITIES))
                .and(user.status.eq(UserStatus.ACTIVE))
                .and(blog.status.eq(BlogStatus.ACTIVE));
    }

    public static BooleanExpression bodyVisible() {
        // 004: 보호 글(PROTECTED)은 목록 노출 가능이지만 본문 노출 가능이 아니다.
        return listable().and(post.visibility.eq(PostVisibility.PUBLIC));
    }

    /** 이미 읽은 글(블로그·작성자 포함)이 목록 노출 가능인지. */
    public static boolean isListable(Post p) {
        return p.getStatus() == PostStatus.PUBLISHED
                && LISTABLE_VISIBILITIES.contains(p.getVisibility())
                && p.getBlog().isActive()
                && p.getBlog().getUser().isActive();
    }

    /** 이미 읽은 글(블로그·작성자 포함)을 주인 외에게 보여도 되는지(상세, 조회수). */
    public static boolean isBodyVisible(Post p) {
        return isListable(p) && p.getVisibility() == PostVisibility.PUBLIC;
    }

    /**
     * 이 사람이 글 상세를 볼 수 있는지(매트릭스 "상세" 열). 주인은 DRAFT·PRIVATE도 보지만 DELETED는 휴지통에서만 보므로 상세는 404,
     * 삭제된 블로그·정지·탈퇴 회원의 글은 주인에게도 404다.
     */
    public static boolean isDetailVisibleTo(Post p, Long viewerId) {
        if (p.isOwnedBy(viewerId)) {
            return !p.isDeleted() && p.getBlog().isActive() && p.getBlog().getUser().isActive();
        }
        return isBodyVisible(p);
    }
}
