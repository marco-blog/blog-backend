package net.java21.blog.backend.media.controller;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.repository.MediaFileRow;
import net.java21.blog.backend.media.repository.MediaQueryRepository;
import net.java21.blog.backend.media.service.MediaKeyGenerator;
import net.java21.blog.backend.media.storage.MediaStorage;
import net.java21.blog.backend.media.thumbnail.Fit;
import net.java21.blog.backend.media.thumbnail.ThumbnailService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이미지 제공(T218, FR-130~132, FR-156, research R11·R27). 접두어 {@code /api/v1} 없이 {@code /media/**}.
 * <ul>
 *   <li>TEMP는 올린 회원에게만 {@code Cache-Control: private, no-store}로, 그 외(비로그인 포함)에는 404 {@code MEDIA_NOT_FOUND}.</li>
 *   <li>ATTACHED·ORPHANED는 누구나, {@code public, max-age=31536000, immutable}(키가 바뀌지 않으므로).</li>
 *   <li>키 형식이 아니거나 없는 키·정리된 이미지는 404. 응답에는 저장된 형식의 {@code Content-Type}, {@code nosniff},
 *       {@code Content-Disposition: inline}을 붙인다.</li>
 * </ul>
 */
@RestController
public class MediaServeController {

    private static final CacheControl PUBLIC_IMMUTABLE = CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable();
    private static final CacheControl PRIVATE_NO_STORE = CacheControl.noStore().cachePrivate();

    private final MediaQueryRepository mediaQueryRepository;
    private final MediaStorage storage;
    private final ThumbnailService thumbnailService;

    public MediaServeController(MediaQueryRepository mediaQueryRepository, MediaStorage storage,
            ThumbnailService thumbnailService) {
        this.mediaQueryRepository = mediaQueryRepository;
        this.storage = storage;
        this.thumbnailService = thumbnailService;
    }

    @GetMapping("/media/{key}")
    ResponseEntity<Resource> original(@CurrentUser(required = false) AuthUser viewer, @PathVariable String key)
            throws IOException {
        MediaFileRow media = visible(key, viewer);
        Resource file = storage.open(media.isTemp() ? MediaStorage.Area.TEMP : MediaStorage.Area.UPLOAD,
                media.storedPath());
        if (!file.exists()) {
            throw notFound(key);
        }
        return image(media, file, media.mime());
    }

    @GetMapping("/media/{key}/{size}")
    ResponseEntity<Resource> thumbnail(@CurrentUser(required = false) AuthUser viewer, @PathVariable String key,
            @PathVariable String size, @RequestParam(required = false) String fit) throws IOException {
        MediaFileRow media = visible(key, viewer);
        Fit parsed = Fit.parse(fit);
        if (parsed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("fit", "INVALID")));
        }
        ThumbnailService.ThumbnailFile thumbnail = thumbnailService.thumbnail(media, size, parsed);
        return image(media, new FileSystemResource(thumbnail.path()), thumbnail.mime());
    }

    private MediaFileRow visible(String key, AuthUser viewer) {
        if (!MediaKeyGenerator.isKey(key)) {
            throw notFound(key);
        }
        MediaFileRow media = mediaQueryRepository.findFile(key).orElseThrow(() -> notFound(key));
        if (media.isTemp() && (viewer == null || viewer.userId() != media.ownerId())) {
            throw notFound(key);
        }
        return media;
    }

    private static ResponseEntity<Resource> image(MediaFileRow media, Resource file, String mime) throws IOException {
        return ResponseEntity.ok()
                .cacheControl(media.isTemp() ? PRIVATE_NO_STORE : PUBLIC_IMMUTABLE)
                .contentType(MediaType.parseMediaType(mime))
                .contentLength(file.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file);
    }

    private static BusinessException notFound(String key) {
        return new BusinessException(ErrorCode.MEDIA_NOT_FOUND, "Media not found");
    }
}
