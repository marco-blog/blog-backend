package net.java21.blog.backend.trackback;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.trackback.domain.Trackback;

/**
 * 트랙백 노출 판단의 한 곳(005 FR-049·051·053·055, research M13·M14). 쿼리 쪽 조건은 {@code TrackbackQueryRepository}가 같은 규칙을
 * {@link PostExposure#bodyVisible(net.java21.blog.backend.post.domain.QPost, net.java21.blog.backend.blog.domain.QBlog,
 * net.java21.blog.backend.user.domain.QUser)}로 쓴다.
 */
public final class TrackbackVisibility {

    private TrackbackVisibility() {
    }

    /** 이 글이 지금 핑을 받는지: 본문 노출 가능(공개·발행·작성자와 블로그 ACTIVE) + 블로그 트랙백 받기. 블로그·주인을 읽어 둔 글. */
    public static boolean acceptsPings(Post post) {
        return PostExposure.isBodyVisible(post) && post.getBlog().isTrackbackEnabled();
    }

    /** 이미 읽은 트랙백이 공개 목록에 나오는지: ACTIVE이고, 서비스 안 출처면 그 글이 본문 노출 가능. */
    public static boolean isListed(Trackback trackback) {
        return trackback.isActive()
                && (trackback.getSourcePost() == null || PostExposure.isBodyVisible(trackback.getSourcePost()));
    }
}
