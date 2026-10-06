package net.java21.blog.backend.user.repository;

import static net.java21.blog.backend.auth.domain.QPasswordResetToken.passwordResetToken;
import static net.java21.blog.backend.auth.domain.QRefreshToken.refreshToken;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.springframework.stereotype.Repository;

/** 개인정보 파기 작업(FR-138·139, research R26)의 조회·삭제. 건수 단위로 나눠 부른다. */
@Repository
public class PrivacyPurgeRepository {

    private final JPAQueryFactory queryFactory;

    public PrivacyPurgeRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** {@code withdrawn_at < cutoff}이고 아직 파기하지 않은 탈퇴 회원 id(오래된 순, 최대 {@code limit}개). */
    public List<Long> findPurgeableUserIds(Instant cutoff, int limit) {
        return queryFactory.select(user.id)
                .from(user)
                .where(user.status.eq(UserStatus.WITHDRAWN), user.withdrawnAt.lt(cutoff),
                        user.passwordHash.ne(User.PURGED_PASSWORD_HASH))
                .orderBy(user.withdrawnAt.asc(), user.id.asc())
                .limit(limit)
                .fetch();
    }

    public List<User> findUsers(List<Long> ids) {
        return queryFactory.selectFrom(user).where(user.id.in(ids)).fetch();
    }

    /** 만료된 비밀번호 재설정 토큰 id(최대 {@code limit}개). */
    public List<Long> findExpiredResetTokenIds(Instant now, int limit) {
        return queryFactory.select(passwordResetToken.id)
                .from(passwordResetToken)
                .where(passwordResetToken.expiresAt.lt(now))
                .orderBy(passwordResetToken.id.asc())
                .limit(limit)
                .fetch();
    }

    public long deleteResetTokens(List<Long> ids) {
        return ids.isEmpty() ? 0
                : queryFactory.delete(passwordResetToken).where(passwordResetToken.id.in(ids)).execute();
    }

    /**
     * 로그인 계열의 절대 만료가 지난 리프레시 토큰 id(최대 {@code limit}개). 유휴 만료만 지난 토큰은 계열이 끝날 때까지 남겨
     * 사용된 토큰의 재사용 감지(계열 폐기)가 계속 동작하게 한다.
     */
    public List<Long> findExpiredRefreshTokenIds(Instant now, int limit) {
        return queryFactory.select(refreshToken.id)
                .from(refreshToken)
                .where(refreshToken.familyExpiresAt.lt(now))
                .orderBy(refreshToken.id.asc())
                .limit(limit)
                .fetch();
    }

    public long deleteRefreshTokens(List<Long> ids) {
        return ids.isEmpty() ? 0 : queryFactory.delete(refreshToken).where(refreshToken.id.in(ids)).execute();
    }
}
