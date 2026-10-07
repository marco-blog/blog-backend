package net.java21.blog.backend.user.repository;

import static net.java21.blog.backend.auth.domain.QPasswordResetToken.passwordResetToken;
import static net.java21.blog.backend.auth.domain.QRefreshToken.refreshToken;
import static net.java21.blog.backend.comment.domain.QComment.comment;
import static net.java21.blog.backend.guestbook.domain.QGuestbookEntry.guestbookEntry;
import static net.java21.blog.backend.report.domain.QReport.report;
import static net.java21.blog.backend.trackback.domain.QTrackback.trackback;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.springframework.stereotype.Repository;

/**
 * 개인정보 파기 작업(FR-138·139, research R26)의 조회·삭제. 건수 단위로 나눠 부른다.
 * 004: 보관 기간이 지난 비회원 댓글·방명록의 IP({@code guest_ip_enc})만 비운다(001 FR-134, research B6).
 * 005: 처리 후 보관 기간이 지난 권리 침해 신고의 연락 이메일({@code contact_email_enc}), 보관 기간이 지난 트랙백 송신 IP
 * ({@code sender_ip_enc})만 비운다(005 data-model reports·trackbacks).
 */
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

    /** 보관 기간이 지난 비회원 댓글 중 IP가 남은 id(오래된 순, 최대 {@code limit}개). */
    public List<Long> findGuestIpCommentIds(Instant cutoff, int limit) {
        return queryFactory.select(comment.id)
                .from(comment)
                .where(comment.user.isNull(), comment.guestIp.isNotNull(), comment.createdAt.lt(cutoff))
                .orderBy(comment.id.asc())
                .limit(limit)
                .fetch();
    }

    /** 보관 기간이 지난 비회원 방명록 중 IP가 남은 id(오래된 순, 최대 {@code limit}개). */
    public List<Long> findGuestIpGuestbookIds(Instant cutoff, int limit) {
        return queryFactory.select(guestbookEntry.id)
                .from(guestbookEntry)
                .where(guestbookEntry.user.isNull(), guestbookEntry.guestIp.isNotNull(),
                        guestbookEntry.createdAt.lt(cutoff))
                .orderBy(guestbookEntry.id.asc())
                .limit(limit)
                .fetch();
    }

    /** 비회원 댓글의 IP만 비운다(내용·이름·비밀번호 해시는 그대로). */
    public long clearCommentGuestIp(List<Long> ids) {
        return ids.isEmpty() ? 0
                : queryFactory.update(comment).setNull(comment.guestIp)
                        .where(comment.id.in(ids), comment.user.isNull()).execute();
    }

    /** 비회원 방명록의 IP만 비운다(내용·이름·비밀번호 해시는 그대로). */
    public long clearGuestbookGuestIp(List<Long> ids) {
        return ids.isEmpty() ? 0
                : queryFactory.update(guestbookEntry).setNull(guestbookEntry.guestIp)
                        .where(guestbookEntry.id.in(ids), guestbookEntry.user.isNull()).execute();
    }

    /** 처리({@code handled_at})한 지 보관 기간이 지난 권리 침해 신고 중 연락 이메일이 남은 id(오래된 순, 최대 {@code limit}개). */
    public List<Long> findExpiredRightsContactIds(Instant cutoff, int limit) {
        return queryFactory.select(report.id)
                .from(report)
                .where(report.channel.eq(ReportChannel.RIGHTS_REQUEST), report.contactEmail.isNotNull(),
                        report.status.ne(ReportStatus.PENDING), report.handledAt.lt(cutoff))
                .orderBy(report.id.asc())
                .limit(limit)
                .fetch();
    }

    /** 권리 침해 신고의 연락 이메일만 비운다(나머지 내용은 그대로). */
    public long clearRightsContact(List<Long> ids) {
        return ids.isEmpty() ? 0
                : queryFactory.update(report).setNull(report.contactEmail)
                        .where(report.id.in(ids), report.channel.eq(ReportChannel.RIGHTS_REQUEST)).execute();
    }

    /** 받은 지 보관 기간이 지난 트랙백 중 송신 IP가 남은 id(오래된 순, 최대 {@code limit}개). */
    public List<Long> findExpiredTrackbackIpIds(Instant cutoff, int limit) {
        return queryFactory.select(trackback.id)
                .from(trackback)
                .where(trackback.senderIp.isNotNull(), trackback.createdAt.lt(cutoff))
                .orderBy(trackback.id.asc())
                .limit(limit)
                .fetch();
    }

    /** 트랙백 송신 IP만 비운다. */
    public long clearTrackbackIp(List<Long> ids) {
        return ids.isEmpty() ? 0
                : queryFactory.update(trackback).setNull(trackback.senderIp).where(trackback.id.in(ids)).execute();
    }
}
