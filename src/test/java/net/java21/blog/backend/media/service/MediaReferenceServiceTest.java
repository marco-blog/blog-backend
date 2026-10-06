package net.java21.blog.backend.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import net.java21.blog.backend.media.TestImages;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.media.domain.PostMedia;
import net.java21.blog.backend.media.domain.PostMediaId;
import net.java21.blog.backend.media.domain.PostMediaSource;
import net.java21.blog.backend.media.repository.MediaQueryRepository;
import net.java21.blog.backend.media.repository.MediaRepository;
import net.java21.blog.backend.media.repository.PostMediaRepository;
import net.java21.blog.backend.media.storage.LocalMediaStorage;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 참조 갱신과 정리 대상 판단(T201, FR-071·073, AS4, quickstart #13·14). 저장소는 흉내 내고 파일 이동은 {@code @TempDir}에서 실제로 한다.
 * 쿼리 자체(참조 존재 확인)는 {@code MediaRepositoryTest}가 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class MediaReferenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:00:00Z");
    private static final long AUTHOR = 7L;
    private static final Long POST = 100L;
    private static final String KEY_A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String KEY_B = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final String KEY_C = "CCCCCCCCCCCCCCCCCCCCCC";

    @TempDir
    Path root;

    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private MediaQueryRepository mediaQueryRepository;
    @Mock
    private PostMediaRepository postMediaRepository;

    private LocalMediaStorage storage;
    private MediaReferenceService service;
    private User author;

    @BeforeEach
    void setUp() {
        storage = new LocalMediaStorage(TestImages.properties(root));
        service = new MediaReferenceService(mediaRepository, mediaQueryRepository, postMediaRepository, storage,
                Clock.fixed(NOW, ZoneOffset.UTC));
        author = TestEntities.user(AUTHOR);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Media media(long id, String key, MediaStatus status, MediaPurpose purpose) throws IOException {
        String name = key.toLowerCase() + ".png";
        Media media = new Media(author, key, purpose, name, status == MediaStatus.TEMP ? name : "2026/09/" + name,
                "image/png", 10, 1, 1);
        TestEntities.with(media, "id", id);
        TestEntities.with(media, "status", status);
        if (status == MediaStatus.TEMP) {
            Files.write(root.resolve("temp").resolve(name), new byte[] {1, 2, 3});
        }
        return media;
    }

    @Test
    void extractsMediaKeysFromMarkdownInOrderWithoutDuplicates() {
        String markdown = """
                ![a](/media/%s) 글 ![b](/media/%s/300x200?fit=contain)
                ![again](/media/%s) [link](https://example.com/media/%s)
                ![too-long](/media/%sX) ![short](/media/ABC) ![other](/images/%s)
                """.formatted(KEY_A, KEY_B, KEY_A, KEY_C, KEY_C, KEY_C);
        // 다른 사이트의 /media/ 주소도 키 모양이면 뽑지만, 본인 이미지 조회에서 걸러진다.
        assertThat(MediaReferenceService.extractKeys(markdown)).containsExactly(KEY_A, KEY_B, KEY_C);
        assertThat(MediaReferenceService.extractKeys(null)).isEmpty();
        assertThat(MediaReferenceService.extractKeys("no images")).isEmpty();
    }

    @Test
    void draftSaveAttachesOwnImagesMovesTempFilesAndReplacesDraftRows() throws IOException {
        Media attached = media(2L, KEY_A, MediaStatus.ATTACHED, MediaPurpose.POST);
        Media temp = media(3L, KEY_B, MediaStatus.TEMP, MediaPurpose.POST);
        when(postMediaRepository.findMediaIds(POST, PostMediaSource.DRAFT)).thenReturn(List.of(1L, 2L));
        when(mediaQueryRepository.findOwnedByKeys(AUTHOR, Set.of(KEY_A, KEY_B, KEY_C))).thenReturn(List.of(attached, temp));

        service.syncDraft(POST, AUTHOR, "![a](/media/%s) ![b](/media/%s) ![남의 것](/media/%s)".formatted(KEY_A, KEY_B,
                KEY_C));

        // TEMP → ATTACHED: temp-dir에서 upload-dir/yyyy/MM/로 이동, 주소(키)는 그대로
        assertThat(temp.getStatus()).isEqualTo(MediaStatus.ATTACHED);
        assertThat(temp.getStoredPath()).isEqualTo("2026/10/bbbbbbbbbbbbbbbbbbbbbb.png");
        assertThat(temp.url()).isEqualTo("/media/" + KEY_B);
        assertThat(root.resolve("upload/2026/10/bbbbbbbbbbbbbbbbbbbbbb.png")).exists();
        assertThat(root.resolve("temp/bbbbbbbbbbbbbbbbbbbbbb.png")).doesNotExist();

        InOrder order = inOrder(postMediaRepository, mediaQueryRepository);
        order.verify(postMediaRepository).deleteByPostAndSource(POST, PostMediaSource.DRAFT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PostMedia>> rows = ArgumentCaptor.forClass(List.class);
        order.verify(postMediaRepository).saveAll(rows.capture());
        // 이전에만 있던 1번만 다시 판단(2번은 계속 참조)
        order.verify(mediaQueryRepository).markOrphanedIfUnreferenced(Set.of(1L));
        assertThat(rows.getValue()).extracting(PostMedia::getId).containsExactly(
                new PostMediaId(POST, 2L, PostMediaSource.DRAFT), new PostMediaId(POST, 3L, PostMediaSource.DRAFT));
        assertThat(rows.getValue()).allSatisfy(row -> {
            assertThat(row.isNew()).isTrue();
            assertThat(row.getCreatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void onlyImagesUploadedByThePostAuthorAreLinked() {
        when(mediaQueryRepository.findOwnedByKeys(AUTHOR, Set.of(KEY_C))).thenReturn(List.of());

        service.syncDraft(POST, AUTHOR, "![남의 것](/media/" + KEY_C + ")");

        verify(mediaQueryRepository).findOwnedByKeys(AUTHOR, Set.of(KEY_C));
        verify(postMediaRepository, never()).saveAll(anyCollection());
        verify(mediaQueryRepository, never()).markOrphanedIfUnreferenced(anyCollection());
    }

    @Test
    void publishReplacesPublishedRowsDeletesDraftRowsAndReevaluatesDroppedImages() throws IOException {
        Media kept = media(2L, KEY_A, MediaStatus.ATTACHED, MediaPurpose.POST);
        when(postMediaRepository.findMediaIds(POST, PostMediaSource.PUBLISHED)).thenReturn(List.of(1L, 2L));
        when(postMediaRepository.findMediaIds(POST, PostMediaSource.DRAFT)).thenReturn(List.of(2L, 4L));
        when(mediaQueryRepository.findOwnedByKeys(AUTHOR, Set.of(KEY_A))).thenReturn(List.of(kept));

        service.syncPublished(POST, AUTHOR, "![a](/media/" + KEY_A + ")");

        verify(postMediaRepository).deleteByPostAndSource(POST, PostMediaSource.PUBLISHED);
        verify(postMediaRepository).deleteByPostAndSource(POST, PostMediaSource.DRAFT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PostMedia>> rows = ArgumentCaptor.forClass(List.class);
        verify(postMediaRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(PostMedia::getId)
                .containsExactly(new PostMediaId(POST, 2L, PostMediaSource.PUBLISHED));
        verify(mediaRepository).flush();
        verify(mediaQueryRepository).markOrphanedIfUnreferenced(Set.of(1L, 4L));
    }

    @Test
    void discardingTheDraftOnlyDeletesDraftRowsAndReevaluatesThem() {
        when(postMediaRepository.findMediaIds(POST, PostMediaSource.DRAFT)).thenReturn(List.of(5L));
        when(mediaQueryRepository.markOrphanedIfUnreferenced(Set.of(5L))).thenReturn(0L);

        service.discardDraft(POST);

        verify(postMediaRepository).deleteByPostAndSource(POST, PostMediaSource.DRAFT);
        verify(postMediaRepository, never()).deleteByPostAndSource(POST, PostMediaSource.PUBLISHED);
        verify(mediaQueryRepository).markOrphanedIfUnreferenced(Set.of(5L));
    }

    @Test
    void orphanedImageReferencedAgainBecomesAttachedWithoutMovingFiles() throws IOException {
        Media orphan = media(9L, KEY_A, MediaStatus.ORPHANED, MediaPurpose.POST);
        when(mediaQueryRepository.findOwnedByKeys(AUTHOR, Set.of(KEY_A))).thenReturn(List.of(orphan));

        service.syncDraft(POST, AUTHOR, "![a](/media/" + KEY_A + ")");

        assertThat(orphan.getStatus()).isEqualTo(MediaStatus.ATTACHED);
        assertThat(orphan.getStoredPath()).isEqualTo("2026/09/aaaaaaaaaaaaaaaaaaaaaa.png");
    }

    @Test
    void failedFileMoveFailsTheSaveSoTheTransactionRollsBack() throws IOException {
        Media temp = media(3L, KEY_B, MediaStatus.TEMP, MediaPurpose.POST);
        Files.delete(root.resolve("temp/bbbbbbbbbbbbbbbbbbbbbb.png"));
        when(mediaQueryRepository.findOwnedByKeys(AUTHOR, Set.of(KEY_B))).thenReturn(List.of(temp));

        assertThatThrownBy(() -> service.syncDraft(POST, AUTHOR, "![b](/media/" + KEY_B + ")"))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(temp.getStatus()).isEqualTo(MediaStatus.TEMP);
        verify(postMediaRepository, never()).saveAll(anyCollection());
    }

    @Test
    void movedFileGoesBackToTempDirWhenTheTransactionRollsBack() throws IOException {
        TransactionSynchronizationManager.initSynchronization();
        Media temp = media(3L, KEY_B, MediaStatus.TEMP, MediaPurpose.PROFILE);

        service.attach(temp);
        assertThat(root.resolve("upload/2026/10/bbbbbbbbbbbbbbbbbbbbbb.png")).exists();

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        assertThat(root.resolve("temp/bbbbbbbbbbbbbbbbbbbbbb.png")).exists();
        assertThat(root.resolve("upload/2026/10/bbbbbbbbbbbbbbbbbbbbbb.png")).doesNotExist();
    }

    @Test
    void committedMoveStaysAndFailedDemoteIsOnlyLogged() throws IOException {
        TransactionSynchronizationManager.initSynchronization();
        Media first = media(3L, KEY_B, MediaStatus.TEMP, MediaPurpose.POST);
        service.attach(first);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        assertThat(root.resolve("upload/2026/10/bbbbbbbbbbbbbbbbbbbbbb.png")).exists();

        // 되돌릴 파일이 없어져도 롤백 처리는 예외를 던지지 않는다.
        Files.delete(root.resolve("upload/2026/10/bbbbbbbbbbbbbbbbbbbbbb.png"));
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    @Test
    void findOwnedAcceptsOnlyTheMembersImageWithTheRightPurpose() throws IOException {
        Media profile = media(3L, KEY_A, MediaStatus.TEMP, MediaPurpose.PROFILE);
        when(mediaQueryRepository.findOwnedByKeys(AUTHOR, List.of(KEY_A))).thenReturn(List.of(profile));

        assertThat(service.findOwned(AUTHOR, KEY_A, MediaPurpose.PROFILE)).isSameAs(profile);
        assertThat(service.findOwned(AUTHOR, KEY_A, MediaPurpose.BLOG_COVER)).isNull();
        assertThat(service.findOwned(AUTHOR, "../etc/passwd", MediaPurpose.PROFILE)).isNull();
    }

    @Test
    void reevaluateWithNothingDoesNotQuery() {
        assertThat(service.reevaluate(List.of())).isZero();
        assertThat(service.reevaluate(null)).isZero();
        verify(mediaQueryRepository, never()).markOrphanedIfUnreferenced(anyCollection());
    }

    @Test
    void permanentPostDeletionDetachesRowsAndReturnsTheirImages() {
        when(postMediaRepository.findMediaIdsOfPosts(List.of(1L, 2L))).thenReturn(List.of(10L, 11L));

        assertThat(service.detachPosts(List.of(1L, 2L))).containsExactly(10L, 11L);
        verify(postMediaRepository).deleteByPosts(List.of(1L, 2L));
        assertThat(service.detachPosts(List.of())).isEmpty();
    }

    @Test
    void coverImagesOfBlogsComeFromTheRepository() {
        when(mediaQueryRepository.findCoverMediaIds(List.of(5L))).thenReturn(List.of(50L));
        assertThat(service.coverMediaIds(List.of(5L))).containsExactly(50L);
    }
}
