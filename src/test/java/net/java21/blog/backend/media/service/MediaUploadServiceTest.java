package net.java21.blog.backend.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.MediaProperties;
import net.java21.blog.backend.media.TestImages;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.media.dto.MediaUploadResponse;
import net.java21.blog.backend.media.repository.MediaQueryRepository;
import net.java21.blog.backend.media.repository.MediaRepository;
import net.java21.blog.backend.media.storage.LocalMediaStorage;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

/**
 * 임시 업로드(T200, FR-038·039, FR-074, FR-156, AS1·2): 크기 413, 회원 임시 한도 429, 내용 415, 무작위 22자 키,
 * {@code {uuid}.{ext}} 저장 이름(원래 이름 안 씀), 용도, TEMP.
 */
@ExtendWith(MockitoExtension.class)
class MediaUploadServiceTest {

    private static final long USER_ID = 7L;

    @TempDir
    Path root;

    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private MediaQueryRepository mediaQueryRepository;
    @Mock
    private UserRepository userRepository;

    private MediaUploadService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = service(TestImages.properties(root));
        user = TestEntities.user(USER_ID);
    }

    private MediaUploadService service(MediaProperties properties) {
        return new MediaUploadService(properties, new ImageInspector(properties), new MediaKeyGenerator(),
                new LocalMediaStorage(properties), mediaRepository, mediaQueryRepository, userRepository);
    }

    private void accepts() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(mediaRepository.save(any(Media.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @ParameterizedTest
    @EnumSource(MediaPurpose.class)
    void storesTempFileWithRandomKeyAndUuidNameNotTheOriginalName(MediaPurpose purpose) throws IOException {
        accepts();
        byte[] png = TestImages.png(30, 20);
        MockMultipartFile file = new MockMultipartFile("file", "../../etc/내 사진.jpg", "image/jpeg", png);

        MediaUploadResponse response = service.upload(USER_ID, file, purpose);

        ArgumentCaptor<Media> saved = ArgumentCaptor.forClass(Media.class);
        verify(mediaRepository).save(saved.capture());
        Media media = saved.getValue();
        assertThat(media.getMediaKey()).matches("^[0-9A-Za-z]{22}$");
        assertThat(media.getStatus()).isEqualTo(MediaStatus.TEMP);
        assertThat(media.getOwnerType()).isEqualTo(purpose);
        assertThat(media.getOwner()).isSameAs(user);
        assertThat(media.getStoredName()).matches("^[0-9a-f-]{36}\\.png$").doesNotContain("사진");
        assertThat(media.getStoredPath()).isEqualTo(media.getStoredName());
        assertThat(media.getMime()).isEqualTo("image/png");
        assertThat(media.getSizeBytes()).isEqualTo(png.length);
        assertThat(Files.readAllBytes(root.resolve("temp").resolve(media.getStoredName()))).isEqualTo(png);
        assertThat(response).isEqualTo(new MediaUploadResponse(media.getMediaKey(), "/media/" + media.getMediaKey(),
                "image/png", png.length, 30, 20));
    }

    @Test
    void tooLargeFileIs413BeforeAnythingIsStored() throws IOException {
        MediaUploadService small = service(
                TestImages.properties(root, DataSize.ofBytes(100), DataSize.ofMegabytes(200), 40_000_000L));
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[101]);

        assertThatThrownBy(() -> small.upload(USER_ID, file, MediaPurpose.POST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TOO_LARGE));
        verify(mediaRepository, never()).save(any());
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void tempQuotaPerMemberIs429() throws IOException {
        byte[] png = TestImages.png(10, 10);
        MediaUploadService quota = service(
                TestImages.properties(root, DataSize.ofMegabytes(10), DataSize.ofBytes(1000), 40_000_000L));
        when(mediaQueryRepository.sumTempBytes(USER_ID)).thenReturn(1000L - png.length + 1);

        assertThatThrownBy(() -> quota.upload(USER_ID, new MockMultipartFile("file", png), MediaPurpose.POST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TEMP_QUOTA_EXCEEDED));
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void exactlyAtQuotaIsAccepted() {
        accepts();
        byte[] png = TestImages.png(10, 10);
        MediaUploadService quota = service(
                TestImages.properties(root, DataSize.ofMegabytes(10), DataSize.ofBytes(1000), 40_000_000L));
        when(mediaQueryRepository.sumTempBytes(USER_ID)).thenReturn(1000L - png.length);

        assertThat(quota.upload(USER_ID, new MockMultipartFile("file", png), MediaPurpose.POST).size())
                .isEqualTo(png.length);
    }

    @Test
    void fakeJpgTextIs415AndNothingIsStored() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "hello".getBytes());

        assertThatThrownBy(() -> service.upload(USER_ID, file, MediaPurpose.POST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TYPE_NOT_ALLOWED));
        verify(mediaRepository, never()).save(any());
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void emptyFileIs415() {
        assertThatThrownBy(() -> service.upload(USER_ID, new MockMultipartFile("file", new byte[0]), MediaPurpose.POST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TYPE_NOT_ALLOWED));
    }

    @Test
    void unreadableUploadIs415() {
        MockMultipartFile broken = new MockMultipartFile("file", new byte[] {1, 2, 3}) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("gone");
            }
        };
        assertThatThrownBy(() -> service.upload(USER_ID, broken, MediaPurpose.POST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TYPE_NOT_ALLOWED));
    }

    @Test
    void storedFileIsRemovedWhenTheRowCannotBeSaved() throws IOException {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(mediaRepository.save(any(Media.class))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.upload(USER_ID, new MockMultipartFile("file", TestImages.png(4, 4)),
                MediaPurpose.POST)).isInstanceOf(IllegalStateException.class);
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void keysAreRandom128BitBase62() {
        MediaKeyGenerator generator = new MediaKeyGenerator(new SecureRandom());
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String key = generator.next();
            assertThat(key).matches("^[0-9A-Za-z]{22}$");
            keys.add(key);
        }
        assertThat(keys).hasSize(1000);
        byte[] zero = new byte[16];
        byte[] max = new byte[16];
        java.util.Arrays.fill(max, (byte) 0xFF);
        assertThat(MediaKeyGenerator.encode(zero)).isEqualTo("0000000000000000000000");
        // 2^128 - 1 = "7n42DGM5Tflk9n8mt7Fhc7" (base62)
        assertThat(MediaKeyGenerator.encode(max)).isEqualTo("7n42DGM5Tflk9n8mt7Fhc7");
        assertThat(MediaKeyGenerator.isKey("k3Jd9fQ2xLmA7pZ0bR5tYw")).isTrue();
        assertThat(MediaKeyGenerator.isKey("../../etc/passwd000000")).isFalse();
        assertThat(MediaKeyGenerator.isKey(null)).isFalse();
    }

    private java.util.List<Path> tempFiles() throws IOException {
        try (Stream<Path> files = Files.list(root.resolve("temp"))) {
            return files.toList();
        }
    }
}
