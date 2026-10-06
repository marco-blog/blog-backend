package net.java21.blog.backend.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.MediaProperties;
import net.java21.blog.backend.media.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

/** 내용으로 이미지 판별(T199, FR-039, R27): 매직 넘버 + 실제로 풀어 보기, 가로·세로, 압축 폭탄 거부. */
class ImageInspectorTest {

    private final ImageInspector inspector = new ImageInspector(TestImages.properties(Path.of("unused")));

    @Test
    void detectsJpegPngGifAndWebpByContentAndReadsSize() {
        assertThat(inspect(TestImages.jpeg(64, 48))).isEqualTo(new ImageInfo("image/jpeg", "jpg", 64, 48));
        assertThat(inspect(TestImages.png(30, 20))).isEqualTo(new ImageInfo("image/png", "png", 30, 20));
        assertThat(inspect(TestImages.gif(12, 34))).isEqualTo(new ImageInfo("image/gif", "gif", 12, 34));
        assertThat(inspect(TestImages.ANIMATED_GIF_20x10)).isEqualTo(new ImageInfo("image/gif", "gif", 20, 10));
        assertThat(inspect(TestImages.WEBP_40x30)).isEqualTo(new ImageInfo("image/webp", "webp", 40, 30));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "just some text pretending to be a .jpg",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
            "<!DOCTYPE html><html><body><img src=x onerror=alert(1)></body></html>",
            "",
            "GIF8"})
    void textSvgHtmlAndTruncatedHeadersAreNotAllowed(String content) {
        assertThatThrownBy(() -> inspect(content.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TYPE_NOT_ALLOWED));
    }

    @Test
    void validMagicNumberWithBrokenBodyIsNotAllowed() {
        byte[] png = TestImages.png(30, 20);
        byte[] broken = Arrays.copyOf(png, 40);
        assertThatThrownBy(() -> inspect(broken))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TYPE_NOT_ALLOWED));
        byte[] jpegHead = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1};
        assertThatThrownBy(() -> inspect(jpegHead)).isInstanceOf(BusinessException.class);
    }

    @Test
    void typesOutsideTheAllowedListAreRejectedEvenIfRealImages() {
        MediaProperties defaults = TestImages.properties(Path.of("unused"));
        MediaProperties pngOnly = new MediaProperties(defaults.uploadDir(), defaults.tempDir(), defaults.thumbnailDir(),
                defaults.tempTtl(), defaults.tempQuota(), defaults.maxSize(), defaults.maxPixels(),
                defaults.cleanupCron(), List.of("image/png"), defaults.thumbnail());
        ImageInspector strict = new ImageInspector(pngOnly);
        assertThat(strict.inspect(new ByteArrayInputStream(TestImages.png(2, 2))).mime()).isEqualTo("image/png");
        assertThatThrownBy(() -> strict.inspect(new ByteArrayInputStream(TestImages.jpeg(2, 2))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TYPE_NOT_ALLOWED));
    }

    @Test
    void tooManyPixelsIsRejectedBeforeDecodingAsDecompressionBomb() {
        ImageInspector small = new ImageInspector(
                TestImages.properties(Path.of("unused"), DataSize.ofMegabytes(10), DataSize.ofMegabytes(200), 1000));
        assertThat(small.inspect(new ByteArrayInputStream(TestImages.png(40, 25))).width()).isEqualTo(40);
        assertThatThrownBy(() -> small.inspect(new ByteArrayInputStream(TestImages.png(40, 26))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TOO_LARGE));
    }

    @Test
    void largeImageIsVerifiedWithSubsampling() {
        assertThat(inspect(TestImages.jpeg(2000, 1500))).isEqualTo(new ImageInfo("image/jpeg", "jpg", 2000, 1500));
    }

    @Test
    void unreadableStreamIsNotAllowed() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };
        assertThatThrownBy(() -> inspector.inspect(failing))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MEDIA_TYPE_NOT_ALLOWED));
    }

    private ImageInfo inspect(byte[] bytes) {
        return inspector.inspect(new ByteArrayInputStream(bytes));
    }
}
