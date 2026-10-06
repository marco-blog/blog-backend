package net.java21.blog.backend.media.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.MediaProperties;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.dto.MediaUploadResponse;
import net.java21.blog.backend.media.repository.MediaQueryRepository;
import net.java21.blog.backend.media.repository.MediaRepository;
import net.java21.blog.backend.media.storage.MediaStorage;
import net.java21.blog.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 임시 업로드(T216, FR-038·039, FR-074, FR-156, AS1·2): 크기 → 회원 임시 한도 → 내용 확인 → temp-dir에 {@code {uuid}.{ext}}로 저장
 * → {@code media} 행(TEMP, 무작위 키). 원래 파일 이름은 어디에도 쓰지 않는다.
 */
@Service
public class MediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);

    private final MediaProperties properties;
    private final ImageInspector inspector;
    private final MediaKeyGenerator keyGenerator;
    private final MediaStorage storage;
    private final MediaRepository mediaRepository;
    private final MediaQueryRepository mediaQueryRepository;
    private final UserRepository userRepository;

    public MediaUploadService(MediaProperties properties, ImageInspector inspector, MediaKeyGenerator keyGenerator,
            MediaStorage storage, MediaRepository mediaRepository, MediaQueryRepository mediaQueryRepository,
            UserRepository userRepository) {
        this.properties = properties;
        this.inspector = inspector;
        this.keyGenerator = keyGenerator;
        this.storage = storage;
        this.mediaRepository = mediaRepository;
        this.mediaQueryRepository = mediaQueryRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public MediaUploadResponse upload(long userId, MultipartFile file, MediaPurpose purpose) {
        long size = file.getSize();
        if (size <= 0) {
            throw new BusinessException(ErrorCode.MEDIA_TYPE_NOT_ALLOWED, "Empty upload");
        }
        if (size > properties.maxSize().toBytes()) {
            throw new BusinessException(ErrorCode.MEDIA_TOO_LARGE, "Upload too large: " + size);
        }
        if (mediaQueryRepository.sumTempBytes(userId) + size > properties.tempQuota().toBytes()) {
            throw new BusinessException(ErrorCode.MEDIA_TEMP_QUOTA_EXCEEDED, "Temporary upload quota exceeded");
        }
        ImageInfo info;
        try (InputStream in = file.getInputStream()) {
            info = inspector.inspect(in);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.MEDIA_TYPE_NOT_ALLOWED, "Unreadable upload");
        }

        String storedName = UUID.randomUUID() + "." + info.extension();
        String tempPath;
        try (InputStream in = file.getInputStream()) {
            tempPath = storage.saveTemp(in, storedName);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store upload", e);
        }
        try {
            Media media = mediaRepository.save(new Media(userRepository.getReferenceById(userId), keyGenerator.next(),
                    purpose, storedName, tempPath, info.mime(), Math.toIntExact(size), info.width(), info.height()));
            mediaRepository.flush();
            return new MediaUploadResponse(media.getMediaKey(), media.url(), media.getMime(), size, media.getWidth(),
                    media.getHeight());
        } catch (RuntimeException e) {
            deleteQuietly(tempPath);
            throw e;
        }
    }

    private void deleteQuietly(String tempPath) {
        try {
            storage.delete(MediaStorage.Area.TEMP, tempPath);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete temporary upload {}", tempPath, e);
        }
    }
}
