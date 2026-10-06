package net.java21.blog.backend.user.job;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.media.service.MediaReferenceService;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import net.java21.blog.backend.user.PrivacyProperties;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.LoginHistoryQueryRepository;
import net.java21.blog.backend.user.repository.PrivacyPurgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 개인정보 파기(T144, FR-138·139, research R26). {@code blog.jobs.privacy-purge-cron}(기본 매일 04:00)마다
 * <ol>
 *   <li>{@code withdrawn_at < 지금 - blog.privacy.withdrawn-retention(30일)}인 탈퇴 회원의 개인정보를 파기한다:
 *       이메일 자리에 {@code withdrawn:{id}}(암호화)와 그 HMAC(NOT NULL·UNIQUE 유지, tasks.md "구현 전 결정 사항" 10번),
 *       닉네임 익명화, 소개·프로필 이미지 NULL(이전 프로필 이미지는 정리 대상 판단, FR-073). 그 회원이 받은 알림도 지운다
 *       (002 data-model notifications).</li>
 *   <li>{@code blog.privacy.login-history-retention(90일)}이 지난 로그인 기록을 지운다.</li>
 *   <li>만료된 비밀번호 재설정 토큰과 절대 만료가 지난 리프레시 토큰을 지운다.</li>
 * </ol>
 * {@code blog.jobs.purge-batch-size}건씩 트랜잭션을 나눠 처리하고 건수를 로그에 남긴다. 서버 1대 전제라 분산 락은 없다.
 */
@Component
public class PrivacyPurgeJob {

    static final String ANONYMOUS_EMAIL_PREFIX = "withdrawn:";

    private static final Logger log = LoggerFactory.getLogger(PrivacyPurgeJob.class);

    private final PrivacyPurgeRepository repository;
    private final LoginHistoryQueryRepository loginHistory;
    private final PersonalDataHasher hasher;
    private final TransactionTemplate transactionTemplate;
    private final PrivacyProperties privacy;
    private final JobsProperties jobs;
    private final Clock clock;
    /** 이미지 참조 정리. 없으면(이미지를 다루지 않는 슬라이스 테스트) 건너뛴다. */
    private final MediaReferenceService mediaReferences;
    /** 받은 알림 삭제. 없으면(알림을 다루지 않는 슬라이스 테스트) 건너뛴다. */
    private final NotificationQueryRepository notifications;

    public PrivacyPurgeJob(PrivacyPurgeRepository repository, LoginHistoryQueryRepository loginHistory,
            PersonalDataHasher hasher, TransactionTemplate transactionTemplate, PrivacyProperties privacy,
            JobsProperties jobs, Clock clock) {
        this(repository, loginHistory, hasher, transactionTemplate, privacy, jobs, clock, null, null);
    }

    public PrivacyPurgeJob(PrivacyPurgeRepository repository, LoginHistoryQueryRepository loginHistory,
            PersonalDataHasher hasher, TransactionTemplate transactionTemplate, PrivacyProperties privacy,
            JobsProperties jobs, Clock clock, MediaReferenceService mediaReferences) {
        this(repository, loginHistory, hasher, transactionTemplate, privacy, jobs, clock, mediaReferences, null);
    }

    @Autowired
    public PrivacyPurgeJob(PrivacyPurgeRepository repository, LoginHistoryQueryRepository loginHistory,
            PersonalDataHasher hasher, TransactionTemplate transactionTemplate, PrivacyProperties privacy,
            JobsProperties jobs, Clock clock, MediaReferenceService mediaReferences,
            NotificationQueryRepository notifications) {
        this.repository = repository;
        this.loginHistory = loginHistory;
        this.hasher = hasher;
        this.transactionTemplate = transactionTemplate;
        this.privacy = privacy;
        this.jobs = jobs;
        this.clock = clock;
        this.mediaReferences = mediaReferences;
        this.notifications = notifications;
    }

    /** 처리 결과(파기한 회원 수, 지운 로그인 기록·재설정 토큰·리프레시 토큰 수). */
    public record Result(long users, long loginHistory, long resetTokens, long refreshTokens) {
    }

    @Scheduled(cron = "${blog.jobs.privacy-purge-cron:0 0 4 * * *}")
    public void run() {
        purge();
    }

    public Result purge() {
        Instant now = clock.instant();
        Instant withdrawnCutoff = now.minus(privacy.withdrawnRetention());
        Instant historyCutoff = now.minus(privacy.loginHistoryRetention());
        int batch = jobs.purgeBatchSize();

        long users = inBatches(() -> repository.findPurgeableUserIds(withdrawnCutoff, batch), this::purgeUsers);
        long history = inBatches(() -> loginHistory.findIdsCreatedBefore(historyCutoff, batch),
                loginHistory::deleteByIds);
        long resetTokens = inBatches(() -> repository.findExpiredResetTokenIds(now, batch),
                repository::deleteResetTokens);
        long refreshTokens = inBatches(() -> repository.findExpiredRefreshTokenIds(now, batch),
                repository::deleteRefreshTokens);
        log.info("Privacy purge finished: users={}, loginHistory={}, resetTokens={}, refreshTokens={}",
                users, history, resetTokens, refreshTokens);
        return new Result(users, history, resetTokens, refreshTokens);
    }

    private long purgeUsers(List<Long> ids) {
        List<User> users = repository.findUsers(ids);
        List<Long> profileMediaIds = new ArrayList<>();
        for (User user : users) {
            if (user.getProfileMediaId() != null) {
                profileMediaIds.add(user.getProfileMediaId());
            }
            String anonymous = ANONYMOUS_EMAIL_PREFIX + user.getId();
            user.purgePersonalData(anonymous, hasher.hash(anonymous));
        }
        if (mediaReferences != null) {
            mediaReferences.reevaluate(profileMediaIds);
        }
        if (notifications != null) {
            notifications.deleteByUserIds(ids);
        }
        return users.size();
    }

    /**
     * 고르기 → 처리를 한 트랜잭션으로, 고른 수가 배치 크기보다 작아질 때까지 되풀이한다.
     * 고른 것을 하나도 처리하지 못했으면 같은 행을 되풀이하지 않도록 멈춘다.
     */
    private long inBatches(Supplier<List<Long>> select, Function<List<Long>, Long> process) {
        long total = 0;
        while (true) {
            long[] batch = transactionTemplate.execute(status -> {
                List<Long> ids = select.get();
                return new long[] {ids.size(), ids.isEmpty() ? 0 : process.apply(ids)};
            });
            if (batch == null) {
                return total;
            }
            total += batch[1];
            if (batch[0] < jobs.purgeBatchSize() || batch[1] == 0) {
                return total;
            }
        }
    }
}
