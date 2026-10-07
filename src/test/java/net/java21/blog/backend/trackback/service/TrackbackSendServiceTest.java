package net.java21.blog.backend.trackback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.repository.TrackbackPingLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** 트랙백 보내기 요청(005 T089, FR-052, AS2, 결정 표 25번). */
@ExtendWith(MockitoExtension.class)
class TrackbackSendServiceTest {

    @Mock
    private TrackbackPingLogRepository repository;
    @Mock
    private ApplicationEventPublisher events;

    private TrackbackSendService service;
    private Post post;

    @BeforeEach
    void setUp() {
        service = new TrackbackSendService(repository, events, TrackbackProperties.defaults());
        post = TestEntities.post(100L, TestEntities.blog(10L, TestEntities.user(1L), "marco"), "글");
    }

    private static List<FieldError> errors(Runnable call) {
        BusinessException e = (BusinessException) org.assertj.core.api.Assertions.catchThrowable(call::run);
        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        return e.fieldErrors();
    }

    @Test
    void validateKeepsOrderAndMergesDuplicateSpellings() {
        assertThat(service.validate(null, PostVisibility.PRIVATE)).isEmpty();
        assertThat(service.validate(List.of(), PostVisibility.PRIVATE)).isEmpty();
        assertThat(service.validate(List.of(" https://a.example/tb ", "HTTPS://A.example:443/tb#x",
                "http://b.example/tb"), PostVisibility.PUBLIC))
                .containsExactly("https://a.example/tb", "http://b.example/tb");
    }

    @Test
    void invalidEntriesAreReportedByIndex() {
        List<FieldError> errors = errors(() -> service.validate(
                Arrays.asList("https://ok.example/", "ftp://x.example/", null, "https://" + "a".repeat(1000)),
                PostVisibility.PUBLIC));
        assertThat(errors).extracting(FieldError::field)
                .containsExactly("trackbackUrls[1]", "trackbackUrls[2]", "trackbackUrls[3]");
        assertThat(errors).extracting(FieldError::code).containsOnly("INVALID");
    }

    @Test
    void moreThanTenDistinctUrlsIsTooLongButDuplicatesCountOnce() {
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("https://e.example/" + i);
        }
        List<FieldError> errors = errors(() -> service.validate(eleven, PostVisibility.PUBLIC));
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("trackbackUrls");
            assertThat(e.code()).isEqualTo("TOO_LONG");
            assertThat(e.params()).containsEntry("max", 10);
        });

        List<String> repeated = new ArrayList<>(eleven.subList(0, 10));
        repeated.add("https://e.example/0");
        assertThat(service.validate(repeated, PostVisibility.PUBLIC)).hasSize(10);
    }

    @Test
    void nonPublicPostsCannotSendTrackbacks() {
        for (PostVisibility visibility : List.of(PostVisibility.PRIVATE, PostVisibility.PROTECTED)) {
            assertThatThrownBy(() -> service.validate(List.of("https://a.example/"), visibility))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.TRACKBACK_NOT_ALLOWED);
        }
        assertThat(errors(() -> service.validate(List.of("bad"), PostVisibility.PRIVATE)))
                .as("형식 오류가 먼저").hasSize(1);
    }

    @Test
    void immediatePublishCreatesPendingLogsAndPublishesTheEventWithAllPendingOfThePost() {
        when(repository.save(any(TrackbackPingLog.class))).thenAnswer(i -> i.getArgument(0));
        TrackbackPingLog older = TestEntities.with(new TrackbackPingLog(post, "https://old.example/"), "id", 7L);
        TrackbackPingLog fresh = TestEntities.with(new TrackbackPingLog(post, "https://a.example/"), "id", 8L);
        when(repository.findByPostIdAndStatus(100L, PingStatus.PENDING)).thenReturn(List.of(older, fresh));

        service.request(post, List.of("https://a.example/", "https://b.example/"), true);

        ArgumentCaptor<TrackbackPingLog> saved = ArgumentCaptor.forClass(TrackbackPingLog.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(TrackbackPingLog::getTargetUrl)
                .containsExactly("https://a.example/", "https://b.example/");
        assertThat(saved.getAllValues()).extracting(TrackbackPingLog::getStatus).containsOnly(PingStatus.PENDING);
        verify(events).publishEvent(new TrackbackSendRequested(100L, List.of(7L, 8L)));
    }

    @Test
    void scheduledPublishOnlyRecords() {
        when(repository.save(any(TrackbackPingLog.class))).thenAnswer(i -> i.getArgument(0));

        service.request(post, List.of("https://a.example/"), false);

        verify(repository).save(any(TrackbackPingLog.class));
        verify(repository, never()).findByPostIdAndStatus(any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void immediatePublishWithoutAnythingPendingPublishesNothing() {
        when(repository.findByPostIdAndStatus(100L, PingStatus.PENDING)).thenReturn(List.of());

        service.request(post, List.of(), true);

        verifyNoInteractions(events);
    }

    @Test
    void dispatchPendingAndDiscardPending() {
        TrackbackPingLog log = TestEntities.with(new TrackbackPingLog(post, "https://a.example/"), "id", 3L);
        when(repository.findByPostIdAndStatus(100L, PingStatus.PENDING)).thenReturn(List.of(log));
        service.dispatchPending(100L);
        verify(events).publishEvent(new TrackbackSendRequested(100L, List.of(3L)));

        when(repository.deleteByPostIdsAndStatus(List.of(100L, 101L), PingStatus.PENDING)).thenReturn(2);
        assertThat(service.discardPending(List.of(100L, 101L))).isEqualTo(2);
        assertThat(service.discardPending(List.of())).isZero();
        verify(repository, times(1)).deleteByPostIdsAndStatus(any(), any());
    }
}
