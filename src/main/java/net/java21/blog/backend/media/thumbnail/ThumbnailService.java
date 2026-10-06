package net.java21.blog.backend.media.thumbnail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.MediaProperties;
import net.java21.blog.backend.media.repository.MediaFileRow;
import net.java21.blog.backend.media.service.MediaKeyGenerator;
import net.java21.blog.backend.media.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

/**
 * 썸네일(T217, FR-130~132, research R11). 허용 목록({@code blog.media.thumbnail.sizes}) 크기만 처음 요청 때 만들어
 * {@code thumbnail-dir/{key}/{w}x{h}-{fit}.{ext}}에 저장하고 이후에는 저장본을 준다.
 * <ul>
 *   <li>원본보다 크게 늘리지 않는다: cover는 비율을 지킨 채 원본 안에서 자르고, contain은 원본이 상자 안에 들어가면 그대로 둔다.</li>
 *   <li>움직이는 GIF는 첫 장면으로 만든다(ImageIO는 첫 프레임만 읽는다). WebP 원본은 PNG로, 나머지는 원본 형식으로.</li>
 *   <li>같은 썸네일 동시 요청: 키별 락 → 락 안에서 다시 확인 → 같은 디렉터리의 임시 파일에 쓴 뒤 {@code ATOMIC_MOVE}.
 *       다른 요청은 완성된 파일만 본다.</li>
 * </ul>
 */
@Service
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    private final MediaStorage storage;
    private final ThumbnailLocks locks;
    private final Path thumbnailDir;
    private final Set<String> allowedSizes;

    public ThumbnailService(MediaProperties properties, MediaStorage storage, ThumbnailLocks locks) {
        this.storage = storage;
        this.locks = locks;
        this.thumbnailDir = properties.thumbnailDir().toAbsolutePath().normalize();
        this.allowedSizes = properties.thumbnail().allowed();
    }

    /** 만들어 둔(또는 지금 만든) 썸네일 파일과 형식. */
    public record ThumbnailFile(Path path, String mime) {
    }

    /**
     * @param size {@code {w}x{h}}. 허용 목록에 없으면 400 {@code THUMBNAIL_SIZE_NOT_ALLOWED}
     */
    public ThumbnailFile thumbnail(MediaFileRow media, String size, Fit fit) {
        int[] box = MediaProperties.Thumbnail.parse(size);
        if (box == null || !allowedSizes.contains(size)) {
            throw new BusinessException(ErrorCode.THUMBNAIL_SIZE_NOT_ALLOWED, "Thumbnail size not allowed: " + size);
        }
        Output output = Output.of(media.mime());
        Path dir = keyDir(media.mediaKey());
        Path target = dir.resolve(size + "-" + fit.fileName() + "." + output.extension);
        if (Files.isRegularFile(target)) {
            return new ThumbnailFile(target, output.mime);
        }
        ReentrantLock lock = locks.lockFor(media.mediaKey() + "/" + target.getFileName());
        lock.lock();
        try {
            if (!Files.isRegularFile(target)) {
                generate(media, box[0], box[1], fit, output, target);
            }
            return new ThumbnailFile(target, output.mime);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create thumbnail for " + media.mediaKey(), e);
        } finally {
            lock.unlock();
        }
    }

    /** 원본을 지울 때 그 이미지의 썸네일 디렉터리를 통째로 지운다. */
    public void deleteAll(String mediaKey) {
        try {
            FileSystemUtils.deleteRecursively(keyDir(mediaKey));
        } catch (IOException e) {
            log.warn("Could not delete thumbnails of {}", mediaKey, e);
        }
    }

    private Path keyDir(String mediaKey) {
        if (!MediaKeyGenerator.isKey(mediaKey)) {
            throw new IllegalArgumentException("Not a media key: " + mediaKey);
        }
        return thumbnailDir.resolve(mediaKey);
    }

    private void generate(MediaFileRow media, int width, int height, Fit fit, Output output, Path target)
            throws IOException {
        int srcW = media.width();
        int srcH = media.height();
        // 원본을 넘지 않는 결과 크기
        int outW;
        int outH;
        if (fit == Fit.COVER) {
            double f = Math.min(1.0, Math.min((double) srcW / width, (double) srcH / height));
            outW = Math.max(1, (int) Math.round(width * f));
            outH = Math.max(1, (int) Math.round(height * f));
        } else {
            double f = Math.min(1.0, Math.min((double) width / srcW, (double) height / srcH));
            outW = Math.max(1, (int) Math.round(srcW * f));
            outH = Math.max(1, (int) Math.round(srcH * f));
        }
        double scale = fit == Fit.COVER
                ? Math.max((double) outW / srcW, (double) outH / srcH)
                : (double) outW / srcW;
        BufferedImage source = normalize(readFirstFrame(media, Math.max(1, (int) Math.floor(1.0 / (scale * 2)))),
                output);

        Files.createDirectories(target.getParent());
        Path partial = Files.createTempFile(target.getParent(), ".thumb-", "." + output.extension);
        try {
            var builder = Thumbnails.of(source).size(outW, outH).outputFormat(output.format);
            if (fit == Fit.COVER) {
                builder.crop(Positions.CENTER);
            } else {
                builder.keepAspectRatio(true);
            }
            if (output == Output.JPEG) {
                builder.outputQuality(0.85);
            }
            builder.toFile(partial.toFile());
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /**
     * 팔레트·흑백 등 색 모델이 다른 원본(GIF의 1비트 팔레트 등)은 크기 조정 결과가 깨지므로 RGB(A)로 옮겨 그린다.
     * JPEG는 알파가 없으므로 RGB, 나머지는 ARGB.
     */
    private static BufferedImage normalize(BufferedImage source, Output output) {
        int type = output == Output.JPEG ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        if (source.getType() == type) {
            return source;
        }
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), type);
        java.awt.Graphics2D g = copy.createGraphics();
        try {
            if (type == BufferedImage.TYPE_INT_RGB) {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, copy.getWidth(), copy.getHeight());
            }
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    /** 첫 장면만, {@code step}칸씩 건너뛰어 읽는다(큰 원본을 작은 썸네일로 만들 때 메모리 절약). */
    private BufferedImage readFirstFrame(MediaFileRow media, int step) throws IOException {
        MediaStorage.Area area = media.isTemp() ? MediaStorage.Area.TEMP : MediaStorage.Area.UPLOAD;
        try (InputStream in = storage.open(area, media.storedPath()).getInputStream();
                ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("No image reader for " + media.mediaKey());
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 출력 형식. WebP는 쓰기를 지원하지 않으므로 PNG. */
    enum Output {
        JPEG("jpg", "jpg", "image/jpeg"),
        PNG("png", "png", "image/png"),
        GIF("gif", "gif", "image/gif");

        final String extension;
        final String format;
        final String mime;

        Output(String extension, String format, String mime) {
            this.extension = extension;
            this.format = format;
            this.mime = mime;
        }

        static Output of(String sourceMime) {
            return switch (sourceMime) {
                case "image/jpeg" -> JPEG;
                case "image/gif" -> GIF;
                default -> PNG;
            };
        }
    }
}
