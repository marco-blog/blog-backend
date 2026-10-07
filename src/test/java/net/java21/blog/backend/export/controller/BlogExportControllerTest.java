package net.java21.blog.backend.export.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.export.domain.ExportStatus;
import net.java21.blog.backend.export.dto.BlogExportResponse;
import net.java21.blog.backend.export.service.BlogExportService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 백업 API(T103): {@code POST} 202 + {@code Location}, 409, 목록, 파일 응답의 {@code application/zip}·{@code Content-Disposition}
 * ({@code {handle}-backup-{yyyyMMdd}.zip})·{@code Cache-Control: no-store}, 404는 공통 틀 JSON, 응답에 파일 경로 없음, 401.
 */
@WebMvcTest(BlogExportController.class)
@Import(WebMvcTestSupport.class)
class BlogExportControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private BlogExportService exportService;

    @Test
    void requestIs202WithLocation() throws Exception {
        when(exportService.request(1L, "marco"))
                .thenReturn(new BlogExportResponse(3L, ExportStatus.PENDING, null, null, NOW, null, null));

        mvc.perform(post("/api/v1/blogs/marco/exports").cookie(authCookies.user(1L)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/blogs/marco/exports/3"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.id").value(3))
                .andExpect(jsonPath("$.result.status").value("PENDING"))
                .andExpect(jsonPath("$.result.filePath").doesNotExist());
    }

    @Test
    void requestErrors() throws Exception {
        mvc.perform(post("/api/v1/blogs/marco/exports"))
                .andExpect(status().isUnauthorized());
        when(exportService.request(1L, "marco"))
                .thenThrow(new BusinessException(ErrorCode.EXPORT_LIMIT_EXCEEDED, "x"));
        mvc.perform(post("/api/v1/blogs/marco/exports").cookie(authCookies.user(1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("EXPORT_LIMIT_EXCEEDED"));
        when(exportService.request(2L, "marco")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        mvc.perform(post("/api/v1/blogs/marco/exports").cookie(authCookies.user(2L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listNeedsLoginAndShowsRecentExports() throws Exception {
        mvc.perform(get("/api/v1/blogs/marco/exports")).andExpect(status().isUnauthorized());
        verifyNoInteractions(exportService);

        when(exportService.list(1L, "marco")).thenReturn(List.of(new BlogExportResponse(3L, ExportStatus.READY,
                1048576L, null, NOW, NOW, NOW.plusSeconds(604800))));
        mvc.perform(get("/api/v1/blogs/marco/exports").cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result[0].status").value("READY"))
                .andExpect(jsonPath("$.result[0].fileSize").value(1048576))
                .andExpect(jsonPath("$.result[0].errorCode").value(nullValue()))
                .andExpect(jsonPath("$.result[0].expiresAt").value("2026-10-13T04:24:19Z"));
    }

    @Test
    void fileIsAZipAttachmentThatIsNotStored() throws Exception {
        byte[] zip = "PK\u0003\u0004data".getBytes(StandardCharsets.ISO_8859_1);
        when(exportService.file(1L, "marco", 3L)).thenReturn(new BlogExportService.ExportFile(
                new ByteArrayResource(zip), "marco-backup-20261006.zip", zip.length));

        MvcResult done = mvc.perform(get("/api/v1/blogs/marco/exports/3/file").cookie(authCookies.user(1L)))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"marco-backup-20261006.zip\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(done.getResponse().getContentAsByteArray()).isEqualTo(zip);
    }

    @Test
    void missingFileIsJsonEnvelope404() throws Exception {
        when(exportService.file(1L, "marco", 9L)).thenThrow(new BusinessException(ErrorCode.EXPORT_NOT_FOUND, "x"));

        mvc.perform(get("/api/v1/blogs/marco/exports/9/file").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.header.resultCode").value("EXPORT_NOT_FOUND"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(get("/api/v1/blogs/marco/exports/9/file")).andExpect(status().isUnauthorized());
    }
}
