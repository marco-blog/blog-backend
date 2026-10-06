package net.java21.blog.backend.releasenote.service;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.releasenote.SemVer;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteQueryRepository;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마지막 확인 릴리스 노트 버전(003 FR-163, research P11). 게시된 버전이어야 하고(아니면 404 {@code RELEASE_NOTE_NOT_FOUND}), 저장된 값보다
 * 높을 때만 바꾼다(SemVer 숫자 비교). 읽은 값을 조건으로 UPDATE하므로 동시에 더 높은 버전이 저장되면 다시 읽어 비교하고, 낮은 값으로 덮어쓰지
 * 않는다.
 */
@Service
public class ReleaseNoteSeenService {

    static final int MAX_ATTEMPTS = 3;

    private final ReleaseNoteQueryRepository queryRepository;
    private final UserRepository userRepository;

    public ReleaseNoteSeenService(ReleaseNoteQueryRepository queryRepository, UserRepository userRepository) {
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
    }

    /**
     * 다시 읽을 때 다른 요청이 커밋한 값을 보도록 READ COMMITTED로 돈다(MySQL 기본 REPEATABLE READ면 같은 스냅숏을 다시 읽는다).
     *
     * @return 저장값을 바꿨는지
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean markSeen(long userId, String version) {
        SemVer next = SemVer.parse(version).orElse(null);
        if (next == null || queryRepository.findPublished(version) == null) {
            throw new BusinessException(ErrorCode.RELEASE_NOTE_NOT_FOUND, "Release note not found: " + version);
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String current = userRepository.findLastSeenReleaseVersion(userId).orElse(null);
            SemVer seen = SemVer.parse(current).orElse(null);
            if (seen != null && !next.isNewerThan(seen)) {
                return false;
            }
            if (userRepository.updateLastSeenReleaseVersion(userId, current, next.toString()) > 0) {
                return true;
            }
        }
        return false;
    }
}
