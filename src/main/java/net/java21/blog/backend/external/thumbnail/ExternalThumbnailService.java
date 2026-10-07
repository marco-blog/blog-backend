package net.java21.blog.backend.external.thumbnail;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.net.FetchResult;
import net.java21.blog.backend.common.net.SafeHttpFetcher;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.media.MediaProperties;
import net.java21.blog.backend.media.service.ImageInfo;
import net.java21.blog.backend.media.service.ImageInspector;
import net.java21.blog.backend.media.service.MediaKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 외부 글 썸네일(007 FR-128, research E7). 소유 인증된 블로그의 글만 대표 이미지를 받아(5MB, {@link SafeHttpFetcher}) 001
 * {@link ImageInspector}로 형식(JPEG·PNG·GIF·WebP)·픽셀 상한을 확인하고 600x400 cover 한 크기만 만든다. 원본은 저장하지 않는다. 경로는
 * {@code {thumbnail-dir}/external/{key 앞 2자}/{key}.{jpg|png}}(JPEG만 jpg, 나머지는 PNG). 받지 못해도 그 글의 썸네일만 없을 뿐이다.
 */
@Service
public class ExternalThumbnailService {

    public static final int WIDTH = 600;
    public static final int HEIGHT = 400;
    /** 소급 대상(최근 30일, 최대 100개). */
    static final Duration BACKFILL_WINDOW = Duration.ofDays(30);
    static final int BACKFILL_MAX = 100;

    private static final Logger log = LoggerFactory.getLogger(ExternalThumbnailService.class);

    /** 저장된 썸네일 파일과 형식. */
    public record StoredFile(Path path, String mime) {
    }

    private final ExternalPostRepository postRepository;
    private final SafeHttpFetcher fetcher;
    private final ImageInspector inspector;
    private final MediaKeyGenerator keys;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Path root;

    public ExternalThumbnailService(ExternalPostRepository postRepository, SafeHttpFetcher fetcher,
            ImageInspector inspector, MediaKeyGenerator keys, MediaProperties media, TransactionTemplate tx,
            Clock clock) {
        this.postRepository = postRepository;
        this.fetcher = fetcher;
        this.inspector = inspector;
        this.keys = keys;
        this.tx = tx;
        this.clock = clock;
        this.root = media.thumbnailDir().toAbsolutePath().normalize().resolve("external");
    }

    /** 글 하나의 썸네일을 받는다(인증된 블로그·ACTIVE·이미지 주소 있음·아직 없음). 만들었으면 키. */
    public Optional<String> fetchFor(long postId) {
        String imageUrl = tx.execute(status -> postRepository.findWithBlog(postId)
                .filter(p -> p.getStatus() == ExternalPostStatus.ACTIVE && p.getThumbnailKey() == null)
                .filter(p -> p.getExternalBlog().isOwnershipVerified())
                .map(ExternalPost::getImageUrl)
                .orElse(null));
        if (imageUrl == null) {
            return Optional.empty();
        }
        String key;
        try {
            key = create(URI.create(imageUrl));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (key == null) {
            return Optional.empty();
        }
        Boolean attached = tx.execute(status -> postRepository.findById(postId)
                .filter(p -> p.getThumbnailKey() == null && p.getStatus() == ExternalPostStatus.ACTIVE)
                .map(p -> {
                    p.attachThumbnail(key);
                    return true;
                })
                .orElse(false));
        if (!Boolean.TRUE.equals(attached)) {
            delete(List.of(key));
            return Optional.empty();
        }
        return Optional.of(key);
    }

    /** 인증 뒤 소급(최근 30일 ACTIVE·이미지 있음·키 없음 최대 100개). 만든 수. */
    public int backfill(long blogId) {
        List<Long> ids = tx.execute(status -> postRepository.findBackfillIds(blogId, ExternalPostStatus.ACTIVE,
                clock.instant().minus(BACKFILL_WINDOW), Limit.of(BACKFILL_MAX)));
        int made = 0;
        for (Long id : ids == null ? List.<Long>of() : ids) {
            if (fetchFor(id).isPresent()) {
                made++;
            }
        }
        return made;
    }

    /** 받아서 줄여 저장한다. 받지 못했거나 이미지가 아니면 null. */
    String create(URI imageUri) {
        FetchResult result = fetcher.get(imageUri, SafeHttpFetcher.Limit.IMAGE);
        if (!result.isSuccess() || result.status() != 200 || result.body() == null) {
            return null;
        }
        ImageInfo info;
        try {
            info = inspector.inspect(new ByteArrayInputStream(result.body()));
        } catch (BusinessException e) {
            return null;
        }
        boolean jpeg = "image/jpeg".equals(info.mime());
        String key = keys.next();
        Path target = path(key, jpeg ? "jpg" : "png");
        try {
            BufferedImage source = readFirstFrame(result.body(), info, jpeg);
            Files.createDirectories(target.getParent());
            Path partial = Files.createTempFile(target.getParent(), ".thumb-", jpeg ? ".jpg" : ".png");
            try {
                // 원본을 넘지 않는 600x400 비율 상자(001 cover와 같은 계산)
                double f = Math.min(1.0, Math.min((double) source.getWidth() / WIDTH,
                        (double) source.getHeight() / HEIGHT));
                int outW = Math.max(1, (int) Math.round(WIDTH * f));
                int outH = Math.max(1, (int) Math.round(HEIGHT * f));
                var builder = Thumbnails.of(source).size(outW, outH).crop(Positions.CENTER)
                        .outputFormat(jpeg ? "jpg" : "png");
                if (jpeg) {
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
            return key;
        } catch (IOException | RuntimeException e) {
            log.info("External thumbnail failed for {}: {}", imageUri.getHost(), e.toString());
            return null;
        }
    }

    /** 제공할 파일(없으면 empty). */
    public Optional<StoredFile> file(String key) {
        if (!MediaKeyGenerator.isKey(key)) {
            return Optional.empty();
        }
        Path jpg = path(key, "jpg");
        if (Files.isRegularFile(jpg)) {
            return Optional.of(new StoredFile(jpg, "image/jpeg"));
        }
        Path png = path(key, "png");
        if (Files.isRegularFile(png)) {
            return Optional.of(new StoredFile(png, "image/png"));
        }
        return Optional.empty();
    }

    /** 키들의 파일을 지운다(없으면 무시). */
    public void delete(Collection<String> keys) {
        for (String key : keys) {
            if (!MediaKeyGenerator.isKey(key)) {
                continue;
            }
            try {
                Files.deleteIfExists(path(key, "jpg"));
                Files.deleteIfExists(path(key, "png"));
            } catch (IOException e) {
                log.warn("Could not delete external thumbnail {}", key, e);
            }
        }
    }

    /** 저장 디렉터리({@code thumbnail-dir/external}). 정리 작업이 DB에 없는 키를 찾을 때 쓴다. */
    public Path root() {
        return root;
    }

    Path path(String key, String extension) {
        return root.resolve(key.substring(0, 2)).resolve(key + "." + extension);
    }

    private static BufferedImage readFirstFrame(byte[] body, ImageInfo info, boolean jpeg) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(body))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("No reader");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.min(info.width() / (WIDTH * 2), info.height() / (HEIGHT * 2)));
                param.setSourceSubsampling(step, step, 0, 0);
                return normalize(reader.read(0, param), jpeg);
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage normalize(BufferedImage source, boolean jpeg) {
        int type = jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        if (source.getType() == type) {
            return source;
        }
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D g = copy.createGraphics();
        try {
            if (jpeg) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, copy.getWidth(), copy.getHeight());
            }
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }
}
