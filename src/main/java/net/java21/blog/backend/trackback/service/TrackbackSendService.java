package net.java21.blog.backend.trackback.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.trackback.TrackbackUrls;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.repository.TrackbackPingLogRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트랙백 보내기 요청(005 FR-052, research M15). 발행 설정의 {@code trackbackUrls}를 검증하고({@link #validate}, 글을 바꾸기 전) 주소마다
 * PENDING 기록을 만든다({@link #request}). 즉시 발행·발행된 글 수정이면 커밋 뒤 보내도록 {@link TrackbackSendRequested}를 내고, 예약
 * 발행이면 기록만 두었다가 004 예약 발행 작업이 {@link #dispatchPending}으로 낸다. 예약 취소·휴지통 이동은 {@link #discardPending}.
 */
@Service
public class TrackbackSendService {

    static final String FIELD = "trackbackUrls";

    private final TrackbackPingLogRepository pingLogRepository;
    private final ApplicationEventPublisher events;
    private final TrackbackProperties properties;

    public TrackbackSendService(TrackbackPingLogRepository pingLogRepository, ApplicationEventPublisher events,
            TrackbackProperties properties) {
        this.pingLogRepository = pingLogRepository;
        this.events = events;
        this.properties = properties;
    }

    /**
     * 보낼 주소 검증. null·빈 목록이면 빈 목록. 형식 오류는 400 {@code trackbackUrls[i] INVALID}, 중복을 뺀 뒤 최대 수를 넘으면 400
     * {@code trackbackUrls TOO_LONG}({@code params.max}), 공개 범위가 PUBLIC이 아니면 422 {@code TRACKBACK_NOT_ALLOWED}.
     *
     * @return 중복(정규화 기준)을 뺀 주소(앞뒤 공백만 뗀 원래 값), 받은 순서
     */
    public List<String> validate(List<String> raw, PostVisibility visibility) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<FieldError> errors = new ArrayList<>();
        Map<String, String> unique = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            Optional<TrackbackUrls.Normalized> url = TrackbackUrls.normalize(raw.get(i));
            if (url.isEmpty()) {
                errors.add(FieldError.of(FIELD + "[" + i + "]", "INVALID"));
            } else {
                unique.putIfAbsent(url.get().hash(), url.get().original());
            }
        }
        if (unique.size() > properties.maxTargets()) {
            errors.add(0, new FieldError(FIELD, "TOO_LONG", Map.of("max", properties.maxTargets())));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", errors);
        }
        if (visibility != PostVisibility.PUBLIC) {
            throw new BusinessException(ErrorCode.TRACKBACK_NOT_ALLOWED,
                    "Trackbacks can only be sent from public posts");
        }
        return List.copyOf(unique.values());
    }

    /**
     * 주소마다 PENDING 기록을 만든다. {@code sendNow}(즉시 발행·발행된 글 수정)면 이 글의 모든 PENDING(예약했다가 바로 발행한 글의 이전
     * 요청 포함)을 커밋 뒤 보내도록 이벤트를 낸다.
     */
    @Transactional
    public void request(Post post, List<String> urls, boolean sendNow) {
        for (String url : urls) {
            pingLogRepository.save(new TrackbackPingLog(post, url));
        }
        if (sendNow) {
            dispatchPending(post.getId());
        }
    }

    /** 예약 글이 실제로 발행된 뒤(004 {@code ScheduledPublishJob}) 그 글의 PENDING을 커밋 뒤 보낸다. */
    @Transactional
    public void dispatchPending(Long postId) {
        List<Long> ids = pingLogRepository.findByPostIdAndStatus(postId, PingStatus.PENDING).stream()
                .map(TrackbackPingLog::getId).toList();
        if (!ids.isEmpty()) {
            events.publishEvent(new TrackbackSendRequested(postId, ids));
        }
    }

    /** 보내지 않은 요청(PENDING)을 지운다(예약 취소·휴지통 이동). */
    @Transactional
    public int discardPending(Collection<Long> postIds) {
        if (postIds.isEmpty()) {
            return 0;
        }
        return pingLogRepository.deleteByPostIdsAndStatus(postIds, PingStatus.PENDING);
    }
}
