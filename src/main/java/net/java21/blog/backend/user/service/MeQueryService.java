package net.java21.blog.backend.user.service;

import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import net.java21.blog.backend.releasenote.ReleaseNoteLanguage;
import net.java21.blog.backend.releasenote.SemVer;
import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteQueryRepository;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.dto.MeResponse;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 회원의 기본 정보와 내 블로그({@code GET /me}). 쿼리 3회(회원, 블로그 목록, 안 읽은 알림 수), 프로필 이미지가 있으면 1회 더,
 * 그리고 릴리스 노트 배너 판단에 최대 2회(최신 게시 노트, 그 제목들, 003 FR-163).
 */
@Service
public class MeQueryService {

    private final UserRepository userRepository;
    private final BlogQueryRepository blogQueryRepository;
    private final NotificationQueryRepository notificationQueryRepository;
    private final ReleaseNoteQueryRepository releaseNoteQueryRepository;

    public MeQueryService(UserRepository userRepository, BlogQueryRepository blogQueryRepository,
            NotificationQueryRepository notificationQueryRepository,
            ReleaseNoteQueryRepository releaseNoteQueryRepository) {
        this.userRepository = userRepository;
        this.blogQueryRepository = blogQueryRepository;
        this.notificationQueryRepository = notificationQueryRepository;
        this.releaseNoteQueryRepository = releaseNoteQueryRepository;
    }

    /** 회원이 없거나 정지·탈퇴했으면 401 {@code UNAUTHENTICATED}(front는 로그아웃 상태로 본다). */
    @Transactional(readOnly = true)
    public MeResponse me(long userId) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Member is not active"));
        return new MeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getBio(), user.profileImageUrl(),
                user.getRole().name(), user.getLocale(), user.getTimeZone(),
                blogQueryRepository.findActiveBlogLinks(user.getId()), unseenReleaseNote(user),
                notificationQueryRepository.countUnread(user.getId()));
    }

    /**
     * 배너 조건(003 FR-163, research P11): 가장 높은 게시 노트가 마지막 확인 버전보다 높고, 가입한 뒤 처음 게시된 노트일 때만
     * {@code { version, title }}(회원 언어판, 요청 → en → ko 대체). 아니면 null.
     */
    private MeResponse.UnseenReleaseNote unseenReleaseNote(User user) {
        ReleaseNote latest = releaseNoteQueryRepository.findLatestPublished();
        if (latest == null || latest.getFirstPublishedAt() == null
                || (user.getCreatedAt() != null && latest.getFirstPublishedAt().isBefore(user.getCreatedAt()))) {
            return null;
        }
        SemVer seen = SemVer.parse(user.getLastSeenReleaseVersion()).orElse(null);
        SemVer version = SemVer.parse(latest.getVersion()).orElse(null);
        if (version == null || (seen != null && !version.isNewerThan(seen))) {
            return null;
        }
        Map<String, String> titles = releaseNoteQueryRepository.findTitles(List.of(latest.getId()))
                .getOrDefault(latest.getId(), Map.of());
        String lang = ReleaseNoteLanguage.pick(titles.keySet(),
                user.getLocale() == null ? ReleaseNoteLanguage.KO : user.getLocale());
        return lang == null ? null : new MeResponse.UnseenReleaseNote(latest.getVersion(), titles.get(lang));
    }
}
