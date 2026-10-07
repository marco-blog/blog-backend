package net.java21.blog.backend.post.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.EnumSet;
import java.util.Set;

import com.querydsl.core.types.dsl.BooleanExpression;

import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.domain.QBlog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.domain.QPost;
import net.java21.blog.backend.user.domain.QUser;
import net.java21.blog.backend.user.domain.UserStatus;

/**
 * 글 노출 조건의 한 곳(T098, FR-018, data-model "노출 조건"·"글 노출 매트릭스").
 * 모든 목록·검색·피드·사이트맵·포털 쿼리는 이 조각만 쓰고, 글 상세의 주인 외 판단도 같은 규칙({@link #isBodyVisible})을 쓴다.
 * <ul>
 *   <li><b>목록 노출 가능</b> {@link #listable()}: {@code status = PUBLISHED AND visibility IN (PUBLIC, PROTECTED) AND 작성자 ACTIVE AND 블로그 ACTIVE}.
 *       보호 글(004)은 목록에 제목만 나온다. 예약 글(SCHEDULED)은 PUBLISHED가 아니므로 어디에도 나오지 않는다.</li>
 *   <li><b>본문 노출 가능</b> {@link #bodyVisible()}: 목록 노출 가능 AND {@code visibility = PUBLIC}.</li>
 *   <li><b>상세</b> {@link #isDetailVisibleTo}: 주인은 휴지통을 뺀 모든 글, 그 외에는 목록 노출 가능 글(보호 글은 잠금 화면,
 *       {@link #isLocked}).</li>
 * </ul>
 * QueryDSL 조각은 기본 별칭 {@code post}·{@code blog}·{@code user}를 쓴다. 쿼리는
 * {@code from(post).join(post.blog, blog).join(blog.user, user)}로 블로그와 작성자를 이어야 한다.
 */
public final class PostExposure {

    /** 목록에 (제목으로라도) 나올 수 있는 공개 범위. 보호 글(004)은 제목만. */
    static final Set<PostVisibility> LISTABLE_VISIBILITIES = EnumSet.of(PostVisibility.PUBLIC,
            PostVisibility.PROTECTED);

    private PostExposure() {
    }

    public static BooleanExpression listable() {
        return listable(post, blog, user);
    }

    public static BooleanExpression bodyVisible() {
        return bodyVisible(post, blog, user);
    }

    /**
     * 별칭을 받는 목록 노출 가능 조건(005 research M14: 트랙백 출처 글처럼 한 쿼리에 글이 둘 이상일 때). 쿼리는 {@code p}의 블로그를
     * {@code b}로, {@code b}의 주인을 {@code u}로 이어야 한다.
     */
    public static BooleanExpression listable(QPost p, QBlog b, QUser u) {
        return p.status.eq(PostStatus.PUBLISHED)
                .and(p.visibility.in(LISTABLE_VISIBILITIES))
                .and(u.status.eq(UserStatus.ACTIVE))
                .and(b.status.eq(BlogStatus.ACTIVE));
    }

    /** 별칭을 받는 본문 노출 가능 조건({@link #listable(QPost, QBlog, QUser)}와 같은 연결 규칙). */
    public static BooleanExpression bodyVisible(QPost p, QBlog b, QUser u) {
        // 004: 보호 글(PROTECTED)은 목록 노출 가능이지만 본문 노출 가능이 아니다.
        return listable(p, b, u).and(p.visibility.eq(PostVisibility.PUBLIC));
    }

    /**
     * 목록에는 나오지만 본문은 볼 수 없는(004 보호 글) 공개 범위가 있는지. 없으면(002) 그런 글만 따로 찾는 조건을 쿼리에 넣지 않는다.
     */
    public static boolean hasListableWithoutBody() {
        return LISTABLE_VISIBILITIES.stream().anyMatch(visibility -> !isBodyVisibleListed(visibility));
    }

    /** 이미 읽은 글(블로그·작성자 포함)이 목록 노출 가능인지. */
    public static boolean isListable(Post p) {
        return p.getStatus() == PostStatus.PUBLISHED
                && LISTABLE_VISIBILITIES.contains(p.getVisibility())
                && p.getBlog().isActive()
                && p.getBlog().getUser().isActive();
    }

    /**
     * {@link #listable()}로 이미 거른 목록 행이 본문 노출 가능인지(공개 범위만 보면 된다). 아니면(004 보호 글) 목록에 제목만 준다
     * ({@code summary}·{@code thumbnailUrl} null, 002 구독 피드·검색).
     */
    public static boolean isBodyVisibleListed(PostVisibility visibility) {
        return visibility == PostVisibility.PUBLIC;
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
        // 004: 보호 글은 주인 외에도 상세(잠금 화면)가 열린다. 본문은 열람 쿠키가 있을 때만(isLocked).
        return isListable(p);
    }

    /**
     * 상세를 볼 수 있는 사람에게 본문을 가려야 하는지(004 FR-062): 보호 글이고, 주인이 아니고, 맞는 비밀번호로 연 적이 없다.
     *
     * @param unlocked 이 요청에 유효한 열람 쿠키({@code post_unlock_{id}})가 있는지
     */
    public static boolean isLocked(Post p, Long viewerId, boolean unlocked) {
        return p.getVisibility() == PostVisibility.PROTECTED && !p.isOwnedBy(viewerId) && !unlocked;
    }
}
