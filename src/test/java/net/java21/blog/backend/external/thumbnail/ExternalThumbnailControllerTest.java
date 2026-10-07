package net.java21.blog.backend.external.thumbnail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.media.TestImages;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 007 T029: {@code GET /media/external/{key}} — 비로그인 허용, 공개 캐시 하루, nosniff·inline, 보일 수 없으면 404. */
@WebMvcTest(ExternalThumbnailController.class)
@Import(WebMvcTestSupport.class)
class ExternalThumbnailControllerTest {

    private static final String KEY = "AbCdEfGhIjKlMnOpQrStUv";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExternalPostRepository postRepository;
    @MockitoBean
    private ExternalThumbnailService thumbnails;

    @TempDir
    Path dir;

    private void post(ExternalPostStatus status, boolean verified) {
        ExternalBlog blog = mock(ExternalBlog.class);
        when(blog.isOwnershipVerified()).thenReturn(verified);
        ExternalPost post = mock(ExternalPost.class);
        when(post.getStatus()).thenReturn(status);
        when(post.getExternalBlog()).thenReturn(blog);
        when(postRepository.findByThumbnailKeyWithBlog(KEY)).thenReturn(Optional.of(post));
    }

    private byte[] file() throws Exception {
        byte[] bytes = TestImages.png(6, 4);
        Path path = dir.resolve(KEY + ".png");
        Files.write(path, bytes);
        when(thumbnails.file(KEY)).thenReturn(Optional.of(new ExternalThumbnailService.StoredFile(path, "image/png")));
        return bytes;
    }

    @Test
    void servesVisibleThumbnailAnonymously() throws Exception {
        post(ExternalPostStatus.ACTIVE, true);
        byte[] bytes = file();

        mvc.perform(get("/media/external/" + KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void badKeyIs404WithoutLookup() throws Exception {
        mvc.perform(get("/media/external/short"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_NOT_FOUND"));
        verifyNoInteractions(postRepository, thumbnails);
    }

    @Test
    void unknownKeyIs404() throws Exception {
        when(postRepository.findByThumbnailKeyWithBlog(KEY)).thenReturn(Optional.empty());
        mvc.perform(get("/media/external/" + KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void removedPostIs404() throws Exception {
        post(ExternalPostStatus.REMOVED, true);
        file();
        mvc.perform(get("/media/external/" + KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void unverifiedBlogIs404() throws Exception {
        post(ExternalPostStatus.ACTIVE, false);
        file();
        mvc.perform(get("/media/external/" + KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingFileIs404() throws Exception {
        post(ExternalPostStatus.ACTIVE, true);
        when(thumbnails.file(KEY)).thenReturn(Optional.empty());
        mvc.perform(get("/media/external/" + KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_NOT_FOUND"));
    }
}
