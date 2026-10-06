package net.java21.blog.backend.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.springframework.util.unit.DataSize;

/** 테스트용 이미지 바이트와 설정. 파일에 쓰지 않고 메모리에서 만든다. */
public final class TestImages {

    /** 40x30 WebP(손실 압축, 왼쪽 빨강·오른쪽 파랑). JDK에는 WebP 쓰기가 없어 미리 만든 바이트를 쓴다. */
    public static final byte[] WEBP_40x30 = Base64.getDecoder().decode(
            "UklGRnoAAABXRUJQVlA4IG4AAABwBACdASooAB4APm0wkkWkIyGYDVQAQAbEoAxQaQfKBSgKv/3AAB9i/aeEAAD+8DhD/FsAZ4LB//aWfqWe"
                    + "6Z+sP+b/vIP257H9z/FO4i/qWKP4p3EX/Hl2RPbylHIw8nPbylFArbF394dyMAAAAA==");

    /** 20x10 움직이는 GIF 2장면: 첫 장면 빨강, 둘째 장면 초록. */
    public static final byte[] ANIMATED_GIF_20x10 = Base64.getDecoder().decode(
            "R0lGODlhFAAKAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAFAAKAAAIGQABCBxIsKDBgwgTKlzIsKHD"
                    + "hxAjSpyoMCAAIfkEAQoAAQAsAAAAABQACgCBAP8AAAAAAAAAAAAACBkAAQgcSLCgwYMIEypcyLChw4cQI0qcqDAgADs=");

    private TestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        return write(image(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    public static byte[] png(int width, int height) {
        return write(image(width, height, BufferedImage.TYPE_INT_ARGB), "png");
    }

    public static byte[] gif(int width, int height) {
        return write(image(width, height, BufferedImage.TYPE_INT_RGB), "gif");
    }

    public static BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 세 디렉터리를 {@code root} 아래에 두는 기본 설정. */
    public static MediaProperties properties(Path root) {
        return properties(root, DataSize.ofMegabytes(10), DataSize.ofMegabytes(200), 40_000_000L);
    }

    public static MediaProperties properties(Path root, DataSize maxSize, DataSize tempQuota, long maxPixels) {
        return new MediaProperties(root.resolve("upload"), root.resolve("temp"), root.resolve("thumb"),
                Duration.ofHours(24), tempQuota, maxSize, maxPixels, "0 0 * * * *",
                List.of("image/jpeg", "image/png", "image/gif", "image/webp"),
                new MediaProperties.Thumbnail(List.of("50x50", "100x100", "160x160", "200x200", "300x200", "320x320",
                        "600x400", "1200x630", "1200x800", "2400x1260")));
    }

    private static BufferedImage image(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 30, 30));
        g.fillRect(0, 0, width / 2, height);
        g.setColor(new Color(30, 30, 200));
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return image;
    }

    private static byte[] write(BufferedImage image, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
