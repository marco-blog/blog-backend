package net.java21.blog.backend.user.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;

import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.domain.PostVisibility;
import org.springframework.stereotype.Repository;

/** 탈퇴 처리의 일괄 변경(FR-009, data-model users 상태 전이). */
@Repository
public class WithdrawalRepository {

    private final JPAQueryFactory queryFactory;

    public WithdrawalRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 그 회원의 모든 블로그(삭제된 블로그 포함)의 모든 글을 비공개로 바꾼다. 이전 공개 범위는 보관하지 않는다. UPDATE 1회.
     * 보호 글(004)의 비밀번호 해시도 지운다({@code ck_posts_protected_password}).
     *
     * @return 바꾼 글 수
     */
    public long makeAllPostsPrivate(long userId) {
        return queryFactory.update(post)
                .set(post.visibility, PostVisibility.PRIVATE)
                .setNull(post.passwordHash)
                .where(post.blog.id.in(JPAExpressions.select(blog.id).from(blog).where(blog.user.id.eq(userId))))
                .execute();
    }
}
