package net.java21.blog.backend.releasenote.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.repository.ReleaseNoteQueryRepository;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 마지막 확인 버전(003 T109, FR-163): 게시되지 않은 버전 404, 높을 때만 갱신(SemVer 숫자 비교), 동시 갱신이면 다시 읽어 비교. */
@ExtendWith(MockitoExtension.class)
class ReleaseNoteSeenServiceTest {

    @Mock
    private ReleaseNoteQueryRepository queryRepository;
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private ReleaseNoteSeenService service;

    @Test
    void unpublishedOrMalformedVersionIs404() {
        when(queryRepository.findPublished("2.0.0")).thenReturn(null);
        expect404("2.0.0");
        expect404("v1");
        expect404(null);
        verify(userRepository, never()).updateLastSeenReleaseVersion(anyLong(), any(), any());
    }

    @Test
    void updatesOnlyWhenNewer() {
        when(queryRepository.findPublished("1.10.0")).thenReturn(org.mockito.Mockito.mock(ReleaseNote.class));
        when(userRepository.findLastSeenReleaseVersion(7L)).thenReturn(Optional.empty(), Optional.of("1.9.0"),
                Optional.of("1.10.0"), Optional.of("1.11.0"));
        when(userRepository.updateLastSeenReleaseVersion(7L, null, "1.10.0")).thenReturn(1);
        when(userRepository.updateLastSeenReleaseVersion(7L, "1.9.0", "1.10.0")).thenReturn(1);

        assertThat(service.markSeen(7L, "1.10.0")).isTrue();
        assertThat(service.markSeen(7L, "1.10.0")).isTrue();
        assertThat(service.markSeen(7L, "1.10.0")).isFalse();
        assertThat(service.markSeen(7L, "1.10.0")).isFalse();
    }

    @Test
    void concurrentHigherValueWinsAfterReread() {
        when(queryRepository.findPublished("1.2.0")).thenReturn(org.mockito.Mockito.mock(ReleaseNote.class));
        when(userRepository.findLastSeenReleaseVersion(7L)).thenReturn(Optional.of("1.1.0"), Optional.of("1.3.0"));
        when(userRepository.updateLastSeenReleaseVersion(7L, "1.1.0", "1.2.0")).thenReturn(0);

        assertThat(service.markSeen(7L, "1.2.0")).isFalse();
        verify(userRepository, times(1)).updateLastSeenReleaseVersion(anyLong(), any(), any());
    }

    @Test
    void givesUpAfterRepeatedConflicts() {
        when(queryRepository.findPublished("1.2.0")).thenReturn(org.mockito.Mockito.mock(ReleaseNote.class));
        when(userRepository.findLastSeenReleaseVersion(7L)).thenReturn(Optional.empty());
        when(userRepository.updateLastSeenReleaseVersion(7L, null, "1.2.0")).thenReturn(0);

        assertThat(service.markSeen(7L, "1.2.0")).isFalse();
        verify(userRepository, times(ReleaseNoteSeenService.MAX_ATTEMPTS))
                .updateLastSeenReleaseVersion(7L, null, "1.2.0");
    }

    private void expect404(String version) {
        assertThatThrownBy(() -> service.markSeen(7L, version)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RELEASE_NOTE_NOT_FOUND));
    }
}
