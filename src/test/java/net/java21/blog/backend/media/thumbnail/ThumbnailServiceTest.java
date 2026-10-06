package net.java21.blog.backend.media.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.TestImages;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.media.repository.MediaFileRow;
import net.java21.blog.backend.media.storage.LocalMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 썸네일(T204, FR-130~132, AS5, quickstart #23): 허용 목록, cover·contain, 원본보다 크게 늘리지 않음, 움직이는 GIF 첫 장면,
 * WebP는 PNG로, 같은 썸네일 20개 동시 요청에도 파일 하나, 저장 위치 {@code thumbnail-dir/{key}/{w}x{h}-{fit}.{ext}}.
 */
class ThumbnailServiceTest {

    private static final String KEY = "k3Jd9fQ2xLmA7pZ0bR5tYw";

    @TempDir
    Path root;

    private LocalMediaStorage storage;
    private ThumbnailService service;

    @BeforeEach
    void setUp() {
        var properties = TestImages.properties(root);
        storage = new LocalMediaStorage(properties);
        service = new ThumbnailService(properties, storage, new ThumbnailLocks());
    }

    private MediaFileRow stored(byte[] bytes, String mime, int width, int height) throws IOException {
        String name = "2026/10/" + KEY + "." + mime.substring(6);
        Path file = root.resolve("upload").resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return new MediaFileRow(1L, KEY, 7L, MediaStatus.ATTACHED, name, mime, width, height);
    }

    private static BufferedImage image(Path file) throws IOException {
        return TestImages.read(Files.readAllBytes(file));
    }

    @Test
    void coverCropsToTheExactSizeAndStoresUnderTheKeyDirectory() throws IOException {
        MediaFileRow media = stored(TestImages.jpeg(900, 900), "image/jpeg", 900, 900);

        ThumbnailService.ThumbnailFile thumb = service.thumbnail(media, "300x200", Fit.COVER);

        assertThat(thumb.path()).isEqualTo(root.resolve("thumb").toAbsolutePath().resolve(KEY + "/300x200-cover.jpg"));
        assertThat(thumb.mime()).isEqualTo("image/jpeg");
        BufferedImage out = image(thumb.path());
        assertThat(out.getWidth()).isEqualTo(300);
        assertThat(out.getHeight()).isEqualTo(200);
    }

    @Test
    void containKeepsTheWholeImageInsideTheBox() throws IOException {
        MediaFileRow media = stored(TestImages.png(900, 300), "image/png", 900, 300);

        ThumbnailService.ThumbnailFile thumb = service.thumbnail(media, "300x200", Fit.CONTAIN);

        assertThat(thumb.path().getFileName().toString()).isEqualTo("300x200-contain.png");
        BufferedImage out = image(thumb.path());
        assertThat(out.getWidth()).isEqualTo(300);
        assertThat(out.getHeight()).isEqualTo(100);
    }

    @Test
    void neverUpscalesBeyondTheOriginal() throws IOException {
        MediaFileRow media = stored(TestImages.png(400, 300), "image/png", 400, 300);

        BufferedImage cover = image(service.thumbnail(media, "2400x1260", Fit.COVER).path());
        assertThat(cover.getWidth()).isLessThanOrEqualTo(400);
        assertThat(cover.getHeight()).isLessThanOrEqualTo(300);
        // 비율(2400:1260)은 지킨 채 원본 안에서 자른다
        assertThat((double) cover.getWidth() / cover.getHeight()).isCloseTo(2400.0 / 1260, org.assertj.core.data.Offset.offset(0.02));

        BufferedImage contain = image(service.thumbnail(media, "1200x800", Fit.CONTAIN).path());
        assertThat(contain.getWidth()).isEqualTo(400);
        assertThat(contain.getHeight()).isEqualTo(300);
    }

    @Test
    void animatedGifUsesTheFirstFrame() throws IOException {
        MediaFileRow media = stored(TestImages.ANIMATED_GIF_20x10, "image/gif", 20, 10);

        ThumbnailService.ThumbnailFile thumb = service.thumbnail(media, "50x50", Fit.COVER);

        assertThat(thumb.mime()).isEqualTo("image/gif");
        BufferedImage out = image(thumb.path());
        int rgb = out.getRGB(out.getWidth() / 2, out.getHeight() / 2);
        // 첫 장면은 빨강, 둘째 장면은 초록
        assertThat((rgb >> 16) & 0xFF).isGreaterThan(200);
        assertThat((rgb >> 8) & 0xFF).isLessThan(50);
    }

    @Test
    void webpOriginalBecomesPng() throws IOException {
        MediaFileRow media = stored(TestImages.WEBP_40x30, "image/webp", 40, 30);

        ThumbnailService.ThumbnailFile thumb = service.thumbnail(media, "50x50", Fit.COVER);

        assertThat(thumb.mime()).isEqualTo("image/png");
        assertThat(thumb.path().getFileName().toString()).isEqualTo("50x50-cover.png");
        assertThat(Files.readAllBytes(thumb.path())).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(image(thumb.path()).getWidth()).isEqualTo(30);
    }

    @ParameterizedTest
    @ValueSource(strings = {"301x200", "0x0", "abc", "300x200x1", "99999x1", "../../x"})
    void sizesOutsideTheAllowListAreRejected(String size) throws IOException {
        MediaFileRow media = stored(TestImages.png(10, 10), "image/png", 10, 10);
        assertThatThrownBy(() -> service.thumbnail(media, size, Fit.COVER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.THUMBNAIL_SIZE_NOT_ALLOWED));
    }

    @Test
    void storedThumbnailIsReusedAndConcurrentRequestsCreateOneFile() throws Exception {
        MediaFileRow media = stored(TestImages.jpeg(1600, 1200), "image/jpeg", 1600, 1200);
        int requests = 20;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Path>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                Callable<Path> call = () -> {
                    start.await();
                    return service.thumbnail(media, "600x400", Fit.COVER).path();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Path> result : results) {
                assertThat(result.get()).hasFileName("600x400-cover.jpg");
            }
        } finally {
            pool.shutdownNow();
        }
        try (Stream<Path> files = Files.list(root.resolve("thumb").resolve(KEY))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("600x400-cover.jpg");
        }
        long modified = Files.getLastModifiedTime(root.resolve("thumb").resolve(KEY).resolve("600x400-cover.jpg"))
                .toMillis();
        service.thumbnail(media, "600x400", Fit.COVER);
        assertThat(Files.getLastModifiedTime(root.resolve("thumb").resolve(KEY).resolve("600x400-cover.jpg"))
                .toMillis()).isEqualTo(modified);
    }

    @Test
    void deleteAllRemovesTheKeyDirectory() throws IOException {
        MediaFileRow media = stored(TestImages.png(10, 10), "image/png", 10, 10);
        service.thumbnail(media, "50x50", Fit.COVER);
        service.thumbnail(media, "50x50", Fit.CONTAIN);

        service.deleteAll(KEY);

        assertThat(root.resolve("thumb").resolve(KEY)).doesNotExist();
        service.deleteAll(KEY);
        assertThatThrownBy(() -> service.deleteAll("../etc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingOriginalFails() {
        MediaFileRow media = new MediaFileRow(1L, KEY, 7L, MediaStatus.TEMP, "gone.png", "image/png", 10, 10);
        assertThatThrownBy(() -> service.thumbnail(media, "50x50", Fit.COVER)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void fitParsing() {
        assertThat(Fit.parse(null)).isEqualTo(Fit.COVER);
        assertThat(Fit.parse("")).isEqualTo(Fit.COVER);
        assertThat(Fit.parse("Contain")).isEqualTo(Fit.CONTAIN);
        assertThat(Fit.parse("cover")).isEqualTo(Fit.COVER);
        assertThat(Fit.parse("stretch")).isNull();
    }

    @Test
    void invalidThumbnailSizePropertyIsRejected() {
        assertThatThrownBy(() -> new net.java21.blog.backend.media.MediaProperties.Thumbnail(List.of("300x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(net.java21.blog.backend.media.MediaProperties.Thumbnail.parse(null)).isNull();
    }
}
