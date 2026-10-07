package net.java21.blog.backend.external.thumbnail;

import java.util.concurrent.TimeUnit;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.media.service.MediaKeyGenerator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 글 썸네일 제공 {@code GET /media/external/{key}}(007 contracts/api.md, research E7). 키 형식이 아니거나 파일이 없거나, 글이
 * ACTIVE가 아니거나 블로그가 소유 인증되지 않았으면 404 {@code MEDIA_NOT_FOUND}. 캐시는 하루(해제·차단 때 내려야 하므로 001의 1년보다
 * 짧게), {@code nosniff}·{@code inline}.
 */
@RestController
public class ExternalThumbnailController {

    static final CacheControl CACHE = CacheControl.maxAge(86400, TimeUnit.SECONDS).cachePublic();

    private final ExternalPostRepository postRepository;
    private final ExternalThumbnailService thumbnails;

    public ExternalThumbnailController(ExternalPostRepository postRepository, ExternalThumbnailService thumbnails) {
        this.postRepository = postRepository;
        this.thumbnails = thumbnails;
    }

    @GetMapping("/media/external/{key}")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> serve(@PathVariable String key) {
        if (!MediaKeyGenerator.isKey(key)) {
            throw notFound(key);
        }
        boolean visible = postRepository.findByThumbnailKeyWithBlog(key)
                .filter(p -> p.getStatus() == ExternalPostStatus.ACTIVE)
                .filter(p -> p.getExternalBlog().isOwnershipVerified())
                .isPresent();
        ExternalThumbnailService.StoredFile file = visible ? thumbnails.file(key).orElse(null) : null;
        if (file == null) {
            throw notFound(key);
        }
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .contentType(MediaType.parseMediaType(file.mime()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .body(new FileSystemResource(file.path()));
    }

    private static BusinessException notFound(String key) {
        return new BusinessException(ErrorCode.MEDIA_NOT_FOUND, "External thumbnail not found: " + key);
    }
}
