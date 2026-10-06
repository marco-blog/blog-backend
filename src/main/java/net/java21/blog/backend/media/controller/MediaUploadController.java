package net.java21.blog.backend.media.controller;

import java.net.URI;
import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.dto.MediaUploadResponse;
import net.java21.blog.backend.media.service.MediaUploadService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 이미지 임시 업로드(T218, contracts/api.md 이미지 절). 로그인 필요, 201 {@code { key, url, mime, size, width, height }}. */
@RestController
public class MediaUploadController {

    private final MediaUploadService uploadService;

    public MediaUploadController(MediaUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(path = "/api/v1/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<MediaUploadResponse>> upload(@CurrentUser AuthUser user,
            @RequestPart("file") MultipartFile file, @RequestParam(required = false) String purpose) {
        MediaUploadResponse uploaded = uploadService.upload(user.userId(), file, parsePurpose(purpose));
        return ResponseEntity.created(URI.create(uploaded.url())).body(ApiResponse.ok(uploaded));
    }

    /** {@code POST}(기본)·{@code PROFILE}·{@code BLOG_COVER}. */
    static MediaPurpose parsePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return MediaPurpose.POST;
        }
        try {
            return MediaPurpose.valueOf(purpose);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("purpose", "INVALID")));
        }
    }
}
