package net.java21.blog.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.blog.dto.BlogLink;
import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
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

/** {@code GET /me} 조회(T094). */
@ExtendWith(MockitoExtension.class)
class MeQueryServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private BlogQueryRepository blogQueryRepository;
    @Mock
    private NotificationQueryRepository notificationQueryRepository;
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
}
