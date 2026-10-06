package net.java21.blog.backend.media.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.TestImages;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.media.dto.MediaUploadResponse;
import net.java21.blog.backend.media.repository.MediaFileRow;
import net.java21.blog.backend.media.repository.MediaQueryRepository;
import net.java21.blog.backend.media.service.MediaUploadService;
import net.java21.blog.backend.media.storage.MediaStorage;
import net.java21.blog.backend.media.thumbnail.Fit;
import net.java21.blog.backend.media.thumbnail.ThumbnailService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 이미지 API(T205, FR-156, AS6, quickstart #24): 업로드 201·401·413, {@code /media/{key}} TEMP는 올린 회원에게만(그 외 404),
 * ATTACHED·ORPHANED는 누구나 긴 캐시, {@code Content-Type}·{@code nosniff}·{@code inline}, 키 형식이 아니면 404, 썸네일.
 */
@WebMvcTest({MediaUploadController.class, MediaServeController.class})
@Import(WebMvcTestSupport.class)
class MediaControllerTest {

    private static final String KEY = "k3Jd9fQ2xLmA7pZ0bR5tYw";
    private static final long OWNER = 7L;

    @TempDir
    Path root;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private MediaUploadService uploadService;
    @MockitoBean
    private MediaQueryRepository mediaQueryRepository;
    @MockitoBean
    private MediaStorage storage;
    @MockitoBean
    private ThumbnailService thumbnailService;

    private byte[] png;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        png = TestImages.png(30, 20);
        file = Files.write(root.resolve("a.png"), png);
    }

    private void stored(MediaStatus status) {
        MediaStorage.Area area = status == MediaStatus.TEMP ? MediaStorage.Area.TEMP : MediaStorage.Area.UPLOAD;
        when(mediaQueryRepository.findFile(KEY)).thenReturn(Optional.of(
                new MediaFileRow(1L, KEY, OWNER, status, "a.png", "image/png", 30, 20)));
        when(storage.open(area, "a.png")).thenReturn(new FileSystemResource(file));
    }

    // ---- 업로드 ----

    @Test
    void uploadReturns201WithKeyUrlAndSize() throws Exception {
        MockMultipartFile part = new MockMultipartFile("file", "photo.png", "image/png", png);
        when(uploadService.upload(eq(OWNER), any(), eq(MediaPurpose.PROFILE))).thenReturn(
                new MediaUploadResponse(KEY, "/media/" + KEY, "image/png", png.length, 30, 20));

        mvc.perform(multipart("/api/v1/media").file(part).param("purpose", "PROFILE").cookie(authCookies.user(OWNER)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/media/" + KEY))
                .andExpect(jsonPath("$.result.key").value(KEY))
                .andExpect(jsonPath("$.result.url").value("/media/" + KEY))
                .andExpect(jsonPath("$.result.mime").value("image/png"))
                .andExpect(jsonPath("$.result.size").value(png.length))
                .andExpect(jsonPath("$.result.width").value(30))
                .andExpect(jsonPath("$.result.height").value(20));
    }

    @Test
    void purposeDefaultsToPostAndUnknownPurposeIs400() throws Exception {
        MockMultipartFile part = new MockMultipartFile("file", "photo.png", "image/png", png);
        when(uploadService.upload(eq(OWNER), any(), eq(MediaPurpose.POST))).thenReturn(
                new MediaUploadResponse(KEY, "/media/" + KEY, "image/png", png.length, 30, 20));
        mvc.perform(multipart("/api/v1/media").file(part).cookie(authCookies.user(OWNER)))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/v1/media").file(part).param("purpose", "AVATAR").cookie(authCookies.user(OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("purpose"));
    }

    @Test
    void anonymousUploadIs401() throws Exception {
        mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file", png)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        verify(uploadService, never()).upload(any(Long.class), any(), any());
    }

    @Test
    void missingFilePartIs400() throws Exception {
        mvc.perform(multipart("/api/v1/media").param("purpose", "POST").cookie(authCookies.user(OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("file"));
    }

    @Test
    void serviceErrorsKeepTheirStatus() throws Exception {
        MockMultipartFile part = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "text".getBytes());
        when(uploadService.upload(eq(OWNER), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.MEDIA_TYPE_NOT_ALLOWED, "no"))
                .thenThrow(new BusinessException(ErrorCode.MEDIA_TEMP_QUOTA_EXCEEDED, "quota"))
                .thenThrow(new MaxUploadSizeExceededException(10));

        mvc.perform(multipart("/api/v1/media").file(part).cookie(authCookies.user(OWNER)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_TYPE_NOT_ALLOWED"));
        mvc.perform(multipart("/api/v1/media").file(part).cookie(authCookies.user(OWNER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_TEMP_QUOTA_EXCEEDED"));
        mvc.perform(multipart("/api/v1/media").file(part).cookie(authCookies.user(OWNER)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_TOO_LARGE"));
    }

    // ---- 원본 제공 ----

    @Test
    void attachedImageIsPublicWithLongCacheAndSafeHeaders() throws Exception {
        stored(MediaStatus.ATTACHED);

        mvc.perform(get("/media/" + KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().longValue("Content-Length", png.length))
                .andExpect(content().bytes(png));
    }

    @Test
    void orphanedImageIsStillServedUntilCleanup() throws Exception {
        stored(MediaStatus.ORPHANED);
        mvc.perform(get("/media/" + KEY)).andExpect(status().isOk());
    }

    @Test
    void tempImageOnlyForTheUploaderWithoutCaching() throws Exception {
        stored(MediaStatus.TEMP);

        mvc.perform(get("/media/" + KEY).cookie(authCookies.user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(content().bytes(png));
        mvc.perform(get("/media/" + KEY).cookie(authCookies.user(8L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_NOT_FOUND"));
        mvc.perform(get("/media/" + KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void unknownKeysMalformedKeysAndMissingFilesAre404() throws Exception {
        when(mediaQueryRepository.findFile(KEY)).thenReturn(Optional.empty());
        mvc.perform(get("/media/" + KEY)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_NOT_FOUND"));
        mvc.perform(get("/media/short")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_NOT_FOUND"));
        mvc.perform(get("/media/..%2F..%2Fetc%2Fpasswd")).andExpect(status().is4xxClientError());

        when(mediaQueryRepository.findFile(KEY)).thenReturn(Optional.of(
                new MediaFileRow(1L, KEY, OWNER, MediaStatus.ATTACHED, "gone.png", "image/png", 30, 20)));
        when(storage.open(MediaStorage.Area.UPLOAD, "gone.png")).thenReturn(new FileSystemResource(root.resolve("x")));
        mvc.perform(get("/media/" + KEY)).andExpect(status().isNotFound());
        verify(mediaQueryRepository, never()).findFile("short");
    }

    // ---- 썸네일 ----

    @Test
    void thumbnailUsesFitAndCachesLikeTheOriginal() throws Exception {
        when(mediaQueryRepository.findFile(KEY)).thenReturn(Optional.of(
                new MediaFileRow(1L, KEY, OWNER, MediaStatus.ATTACHED, "a.png", "image/webp", 30, 20)));
        when(thumbnailService.thumbnail(any(), eq("300x200"), eq(Fit.CONTAIN)))
                .thenReturn(new ThumbnailService.ThumbnailFile(file, "image/png"));
        when(thumbnailService.thumbnail(any(), eq("300x200"), eq(Fit.COVER)))
                .thenReturn(new ThumbnailService.ThumbnailFile(file, "image/png"));

        mvc.perform(get("/media/" + KEY + "/300x200").param("fit", "contain"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(png));
        mvc.perform(get("/media/" + KEY + "/300x200")).andExpect(status().isOk());
        verify(thumbnailService).thumbnail(any(), eq("300x200"), eq(Fit.COVER));
    }

    @Test
    void thumbnailErrors() throws Exception {
        stored(MediaStatus.TEMP);
        when(thumbnailService.thumbnail(any(), eq("301x200"), eq(Fit.COVER)))
                .thenThrow(new BusinessException(ErrorCode.THUMBNAIL_SIZE_NOT_ALLOWED, "no"));

        mvc.perform(get("/media/" + KEY + "/301x200").cookie(authCookies.user(OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("THUMBNAIL_SIZE_NOT_ALLOWED"));
        mvc.perform(get("/media/" + KEY + "/300x200").param("fit", "stretch").cookie(authCookies.user(OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("fit"));
        // 남의 TEMP 썸네일도 404
        mvc.perform(get("/media/" + KEY + "/300x200"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_NOT_FOUND"));
    }
}
