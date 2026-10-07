package net.java21.blog.backend.admin.user;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QLoginHistory.loginHistory;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.admin.user.dto.AdminUserDetail.BlogItem;
import net.java21.blog.backend.admin.user.dto.AdminUserSummary;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.domain.QBlog;
import net.java21.blog.backend.post.domain.PostStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 관리자 회원 검색·상세(005 research M5, 006 FR-104). 목록은 블로그 수를 상관 서브쿼리로 함께 읽어 쿼리 2회(목록, 개수).
 * 이메일은 해시 정확 일치, 블로그 주소는 정확 일치(삭제된 블로그 포함), 닉네임은 앞부분 일치(닉네임순, 인덱스 없음 — plan.md 선택 제안 1).
 */
@Repository
public class AdminUserQueryRepository {

    private static final QBlog owned = new QBlog("owned");

    private final JPAQueryFactory queryFactory;

    public AdminUserQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 검색 조건: 셋 중 하나만 쓴다. */
    public record Search(String emailHash, String nicknamePrefix, String handle) {
    }

    public Page<AdminUserSummary> search(Search search, Pageable pageable) {
        BooleanExpression where = where(search);
        List<AdminUserSummary> rows = queryFactory
                .select(Projections.constructor(AdminUserSummary.class, user.id, user.nickname, user.status,
                        user.role, user.createdAt,
                        JPAExpressions.select(owned.count()).from(owned)
                                .where(owned.user.id.eq(user.id), owned.status.eq(BlogStatus.ACTIVE))))
                .from(user)
                .where(where)
                .orderBy(user.nickname.asc(), user.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(user.count()).from(user).where(where).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 회원의 블로그(삭제 포함, 만든 순). 쿼리 1회. */
    public List<BlogItem> findBlogs(long userId) {
        return queryFactory.select(Projections.constructor(BlogItem.class, blog.handle, blog.title, blog.status))
                .from(blog)
                .where(blog.user.id.eq(userId))
                .orderBy(blog.id.asc())
                .fetch();
    }

    /** 휴지통을 뺀 모든 블로그의 글 수. 쿼리 1회. */
    public long countPosts(long userId) {
        Long count = queryFactory.select(post.count()).from(post)
                .join(post.blog, blog)
                .where(blog.user.id.eq(userId), post.status.ne(PostStatus.DELETED))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 최근 성공한 로그인 시각(없으면 null). 쿼리 1회. */
    public Instant findLastLoginAt(long userId) {
        return queryFactory.select(loginHistory.createdAt.max()).from(loginHistory)
                .where(loginHistory.user.id.eq(userId), loginHistory.success.isTrue())
                .fetchOne();
    }

    private static BooleanExpression where(Search search) {
        if (search.emailHash() != null) {
            return user.emailHash.eq(search.emailHash());
        }
        if (search.handle() != null) {
            return JPAExpressions.selectOne().from(owned)
                    .where(owned.user.id.eq(user.id), owned.handle.eq(search.handle())).exists();
        }
        return user.nickname.startsWith(search.nicknamePrefix());
    }
}
