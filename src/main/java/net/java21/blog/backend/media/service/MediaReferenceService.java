package net.java21.blog.backend.media.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.PostMedia;
import net.java21.blog.backend.media.domain.PostMediaSource;
import net.java21.blog.backend.media.repository.MediaQueryRepository;
import net.java21.blog.backend.media.repository.MediaRepository;
import net.java21.blog.backend.media.repository.PostMediaRepository;
import net.java21.blog.backend.media.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 이미지 참조 갱신과 정리 대상 판단(T216, FR-071·073, data-model media·post_media). 호출한 쪽의 트랜잭션 안에서 돈다.
 * <ul>
 *   <li>글 본문(Markdown)의 {@code /media/{key}} 중 <b>글 작성자 본인이 올린</b> 이미지만 연결한다.</li>
 *   <li>연결할 때 TEMP는 temp-dir에서 {@code upload-dir/yyyy/MM/}로 옮기고 ATTACHED로(주소는 그대로),
 *       ORPHANED는 ATTACHED로 되돌린다. 트랜잭션이 롤백되면 옮긴 파일을 되돌린다.</li>
 *   <li>참조에서 빠진 이미지는 {@code post_media}·프로필·블로그 대표 이미지 어디에서도 쓰지 않을 때만 ORPHANED.</li>
 * </ul>
 */
@Service
public class MediaReferenceService {

    private static final Logger log = LoggerFactory.getLogger(MediaReferenceService.class);

    /** 본문의 이미지 주소(원본·썸네일 모두). 키 뒤에 영숫자가 이어지면 다른 문자열이다. */
    static final Pattern MEDIA_REFERENCE = Pattern.compile("/media/([0-9A-Za-z]{22})(?![0-9A-Za-z])");

    private final MediaRepository mediaRepository;
    private final MediaQueryRepository mediaQueryRepository;
    private final PostMediaRepository postMediaRepository;
    private final MediaStorage storage;
    private final Clock clock;

    public MediaReferenceService(MediaRepository mediaRepository, MediaQueryRepository mediaQueryRepository,
            PostMediaRepository postMediaRepository, MediaStorage storage, Clock clock) {
        this.mediaRepository = mediaRepository;
        this.mediaQueryRepository = mediaQueryRepository;
        this.postMediaRepository = postMediaRepository;
        this.storage = storage;
        this.clock = clock;
    }

    /** 본문에서 이미지 키를 나온 순서대로(중복 없이) 뽑는다. */
    public static Set<String> extractKeys(String markdown) {
        Set<String> keys = new LinkedHashSet<>();
        if (markdown == null) {
            return keys;
        }
        Matcher matcher = MEDIA_REFERENCE.matcher(markdown);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    /** 작성 중 사본 저장: 이 글의 DRAFT 참조를 본문 기준으로 바꾼다. 빠진 이미지는 정리 대상 판단. */
    @Transactional
    public void syncDraft(Long postId, long authorId, String markdown) {
        List<Long> previous = postMediaRepository.findMediaIds(postId, PostMediaSource.DRAFT);
        List<Media> current = attachOwned(authorId, extractKeys(markdown));
        postMediaRepository.deleteByPostAndSource(postId, PostMediaSource.DRAFT);
        insert(postId, PostMediaSource.DRAFT, current);
        reevaluate(without(previous, current));
    }

    /** 발행: PUBLISHED 참조를 새 본문 기준으로 바꾸고 DRAFT 참조를 지운다. 빠진 이미지는 정리 대상 판단. */
    @Transactional
    public void syncPublished(Long postId, long authorId, String markdown) {
        Set<Long> previous = new LinkedHashSet<>(postMediaRepository.findMediaIds(postId, PostMediaSource.PUBLISHED));
        previous.addAll(postMediaRepository.findMediaIds(postId, PostMediaSource.DRAFT));
        List<Media> current = attachOwned(authorId, extractKeys(markdown));
        postMediaRepository.deleteByPostAndSource(postId, PostMediaSource.PUBLISHED);
        postMediaRepository.deleteByPostAndSource(postId, PostMediaSource.DRAFT);
        insert(postId, PostMediaSource.PUBLISHED, current);
        reevaluate(without(previous, current));
    }

    /** 작성 중 사본 폐기: DRAFT 참조만 지우고 판단한다(발행본이 쓰는 이미지는 남는다). */
    @Transactional
    public void discardDraft(Long postId) {
        List<Long> previous = postMediaRepository.findMediaIds(postId, PostMediaSource.DRAFT);
        postMediaRepository.deleteByPostAndSource(postId, PostMediaSource.DRAFT);
        reevaluate(previous);
    }

    /**
     * 프로필·블로그 대표 이미지로 쓸 이미지: 이 회원이 그 용도({@code purpose})로 올린 이미지만. 없거나 남의 것이면 null
     * (호출한 쪽이 입력란 오류 INVALID로 알린다). 아직 연결하지 않는다(다른 입력란 검증 뒤 {@link #attach(Media)}).
     */
    @Transactional(readOnly = true)
    public Media findOwned(long ownerId, String key, MediaPurpose purpose) {
        if (!MediaKeyGenerator.isKey(key)) {
            return null;
        }
        return mediaQueryRepository.findOwnedByKeys(ownerId, List.of(key)).stream()
                .filter(m -> m.getOwnerType() == purpose)
                .findFirst()
                .orElse(null);
    }

    /** ATTACHED로: TEMP면 upload-dir로 옮긴다(롤백 때 되돌림), ORPHANED면 되돌린다. */
    @Transactional
    public void attach(Media media) {
        if (!media.isTemp()) {
            media.reattach();
            return;
        }
        String tempPath = media.getStoredPath();
        String uploadPath;
        try {
            uploadPath = storage.promote(tempPath, YearMonth.now(clock.withZone(ZoneOffset.UTC)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not move media to upload-dir: " + media.getMediaKey(), e);
        }
        media.attachAt(uploadPath);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        demote(uploadPath, tempPath);
                    }
                }
            });
        }
    }

    /**
     * 정리 대상 판단(FR-073): 주어진 이미지 중 어디에서도 참조하지 않는 ATTACHED를 ORPHANED로. 이미지 수와 관계없이 UPDATE 1회.
     */
    @Transactional
    public long reevaluate(Collection<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return 0;
        }
        mediaRepository.flush();
        return mediaQueryRepository.markOrphanedIfUnreferenced(Set.copyOf(mediaIds));
    }

    /** 영구 삭제할 글들의 참조를 지우고 그 이미지 id를 돌려준다(삭제 뒤 {@link #reevaluate}). */
    @Transactional
    public List<Long> detachPosts(Collection<Long> postIds) {
        if (postIds.isEmpty()) {
            return List.of();
        }
        List<Long> mediaIds = postMediaRepository.findMediaIdsOfPosts(postIds);
        postMediaRepository.deleteByPosts(postIds);
        return mediaIds;
    }

    /** 블로그들의 대표 이미지 id(블로그를 비우기 전에 읽어 두고 비운 뒤 {@link #reevaluate}). */
    @Transactional(readOnly = true)
    public List<Long> coverMediaIds(Collection<Long> blogIds) {
        return mediaQueryRepository.findCoverMediaIds(blogIds);
    }

    private List<Media> attachOwned(long authorId, Set<String> keys) {
        List<Media> owned = mediaQueryRepository.findOwnedByKeys(authorId, keys);
        owned.forEach(this::attach);
        return owned;
    }

    private void insert(Long postId, PostMediaSource source, List<Media> media) {
        if (media.isEmpty()) {
            return;
        }
        var now = clock.instant();
        postMediaRepository.saveAll(media.stream()
                .map(m -> new PostMedia(postId, m.getId(), source, now))
                .toList());
    }

    private void demote(String uploadPath, String tempPath) {
        try {
            storage.demote(uploadPath, tempPath);
        } catch (IOException | RuntimeException e) {
            log.error("Could not move media back to temp-dir after rollback: {} -> {}", uploadPath, tempPath, e);
        }
    }

    private static List<Long> without(Collection<Long> previous, List<Media> current) {
        Set<Long> keep = new java.util.HashSet<>();
        current.forEach(m -> keep.add(m.getId()));
        return previous.stream().filter(id -> !keep.contains(id)).distinct().toList();
    }
}
