package net.java21.blog.backend.media.service;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.MediaProperties;
import org.springframework.stereotype.Component;

/**
 * 올린 파일이 실제 이미지인지 내용으로 확인한다(FR-039, research R11·R27). 확장자와 요청의 Content-Type은 믿지 않는다.
 * <ol>
 *   <li>매직 넘버로 jpeg·png·gif·webp만 고른다(SVG·HTML·텍스트는 여기서 415 {@code MEDIA_TYPE_NOT_ALLOWED}).</li>
 *   <li>그 형식의 ImageIO 리더로 머리글의 가로·세로를 읽고, 가로×세로가 {@code blog.media.max-pixels}를 넘으면
 *       압축 폭탄으로 보고 413 {@code MEDIA_TOO_LARGE}(그림을 풀기 전에 거부).</li>
 *   <li>첫 장면을 줄여 끝까지 풀어 본다. 깨진 파일은 415.</li>
 * </ol>
 */
@Component
public class ImageInspector {

    /** 내용 확인 때 풀어 볼 최대 변 길이(이보다 크면 건너뛰며 읽어 메모리를 아낀다). */
    static final int VERIFY_MAX_EDGE = 512;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final MediaProperties properties;

    public ImageInspector(MediaProperties properties) {
        this.properties = properties;
        // WebP 리더(TwelveMonkeys)는 ServiceLoader로 등록된다. 실행 jar의 클래스 로더에서도 찾도록 한 번 다시 찾는다.
        ImageIO.scanForPlugins();
        // 10MB 이하 파일만 다루므로 ImageIO 캐시는 디스크 대신 메모리에 둔다.
        ImageIO.setUseCache(false);
    }

    /** 내용을 확인하고 형식·크기를 돌려준다. 스트림은 닫지 않는다. */
    public ImageInfo inspect(InputStream content) {
        BufferedInputStream in = new BufferedInputStream(content);
        Format format;
        try {
            in.mark(16);
            byte[] head = in.readNBytes(12);
            in.reset();
            format = Format.detect(head);
        } catch (IOException e) {
            throw notAllowed("Unreadable upload");
        }
        if (format == null || !properties.allowedTypes().contains(format.mime)) {
            throw notAllowed("Not an allowed image type");
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            ImageReader reader = readerFor(format, iis);
            try {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw notAllowed("Invalid image size");
                }
                if ((long) width * height > properties.maxPixels()) {
                    throw new BusinessException(ErrorCode.MEDIA_TOO_LARGE,
                            "Image has too many pixels: " + width + "x" + height);
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / VERIFY_MAX_EDGE);
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage decoded = reader.read(0, param);
                if (decoded == null) {
                    throw notAllowed("Image could not be decoded");
                }
                return new ImageInfo(format.mime, format.extension, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException business) {
                throw business;
            }
            throw notAllowed("Image could not be decoded");
        }
    }

    private static ImageReader readerFor(Format format, ImageInputStream iis) {
        if (iis == null) {
            throw notAllowed("Unreadable upload");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.readerName);
        if (!readers.hasNext()) {
            throw new IllegalStateException("No ImageIO reader for " + format.readerName);
        }
        return readers.next();
    }

    private static BusinessException notAllowed(String message) {
        return new BusinessException(ErrorCode.MEDIA_TYPE_NOT_ALLOWED, message);
    }

    /** 허용 형식 4종과 매직 넘버. */
    enum Format {
        JPEG("image/jpeg", "jpg", "jpeg"),
        PNG("image/png", "png", "png"),
        GIF("image/gif", "gif", "gif"),
        WEBP("image/webp", "webp", "webp");

        final String mime;
        final String extension;
        final String readerName;

        Format(String mime, String extension, String readerName) {
            this.mime = mime;
            this.extension = extension;
            this.readerName = readerName;
        }

        static Format detect(byte[] head) {
            if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
                return JPEG;
            }
            if (head.length >= 8 && Arrays.equals(Arrays.copyOf(head, 8), ImageInspector.PNG)) {
                return PNG;
            }
            if (head.length >= 6 && List.of("GIF87a", "GIF89a").contains(ascii(head, 0, 6))) {
                return GIF;
            }
            if (head.length >= 12 && ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP")) {
                return WEBP;
            }
            return null;
        }

        private static String ascii(byte[] bytes, int offset, int length) {
            return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
}
