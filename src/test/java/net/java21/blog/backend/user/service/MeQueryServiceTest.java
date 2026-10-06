package net.java21.blog.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.blog.dto.BlogLink;
import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteQueryRepository;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.dto.MeResponse;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** {@code GET /me} 조회(T094). */
@ExtendWith(MockitoExtension.class)
class MeQueryServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private BlogQueryRepository blogQueryRepository;
    @Mock
    private NotificationQueryRepository notificationQueryRepository;
    @Mock
    private ReleaseNoteQueryRepository releaseNoteQueryRepository;
    @InjectMocks
    private MeQueryService service;

    @Test
    void returnsDecryptedEmailPreferencesAndBlogs() {
        User user = TestEntities.user(7L, "Marco@Example.com", "$2a$hash", "마르코");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        List<BlogLink> blogs = List.of(new BlogLink("marco", "첫째"));
        when(blogQueryRepository.findActiveBlogLinks(7L)).thenReturn(blogs);
        when(notificationQueryRepository.countUnread(7L)).thenReturn(3L);

        MeResponse me = service.me(7L);

        assertThat(me).isEqualTo(new MeResponse(7L, "marco@example.com", "마르코", null, null, "USER", "ko",
                "Asia/Seoul", blogs, null, 3L));
    }

    @Test
    void missingOrInactiveMemberIsUnauthenticated() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        expectUnauthenticated();
        when(userRepository.findById(7L)).thenReturn(Optional.of(
                TestEntities.with(TestEntities.user(7L), "status", UserStatus.WITHDRAWN)));
        expectUnauthenticated();
    }

    private void expectUnauthenticated() {
        assertThatThrownBy(() -> service.me(7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    /** 003 T110(FR-163): 최신 게시 노트가 마지막 확인 버전보다 높고 가입 뒤 게시면 배너. 회원 언어판, 없으면 en → ko. */
    @Test
    void unseenReleaseNoteFollowsBannerRules() {
        Instant joined = Instant.parse("2026-09-01T00:00:00Z");
        User user = TestEntities.with(TestEntities.user(7L), "createdAt", joined);
        TestEntities.with(user, "locale", "ja");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        ReleaseNote latest = new ReleaseNote("1.10.0", 1, 10, 0, LocalDate.of(2026, 10, 6), user);
        ReflectionTestUtils.setField(latest, "id", 3L);
        latest.publish(joined.plusSeconds(60), user);
        when(releaseNoteQueryRepository.findLatestPublished()).thenReturn(latest);
        when(releaseNoteQueryRepository.findTitles(List.of(3L)))
                .thenReturn(Map.of(3L, Map.of("ko", "새 기능", "en", "New features")));

        assertThat(service.me(7L).unseenReleaseNote())
                .isEqualTo(new MeResponse.UnseenReleaseNote("1.10.0", "New features"));

        TestEntities.with(user, "lastSeenReleaseVersion", "1.9.0");
        assertThat(service.me(7L).unseenReleaseNote()).isNotNull();
        TestEntities.with(user, "lastSeenReleaseVersion", "1.10.0");
        assertThat(service.me(7L).unseenReleaseNote()).isNull();
        TestEntities.with(user, "lastSeenReleaseVersion", "2.0.0");
        assertThat(service.me(7L).unseenReleaseNote()).isNull();

        // 가입 전에 게시된 노트는 배너를 띄우지 않는다
        TestEntities.with(user, "lastSeenReleaseVersion", null);
        TestEntities.with(user, "createdAt", joined.plusSeconds(3600));
        assertThat(service.me(7L).unseenReleaseNote()).isNull();

        when(releaseNoteQueryRepository.findLatestPublished()).thenReturn(null);
        assertThat(service.me(7L).unseenReleaseNote()).isNull();
    }
}
