package net.java21.blog.backend.releasenote.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.releasenote.domain.TocEntry;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteDetailResponse;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteListResponse;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteRevisionItem;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteSearchHit;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteSummary;
import net.java21.blog.backend.releasenote.dto.VersionRef;
import net.java21.blog.backend.releasenote.service.ReleaseNoteQueryService;
import net.java21.blog.backend.releasenote.service.ReleaseNoteSeenService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 릴리스 노트 독자 API(003 T111): 응답 모양, {@code lang} 결정(파라미터 → Accept-Language → ko)과 400, {@code {version}} 형식이 아니면
 * 404, {@code /search}가 {@code {version}}과 겹치지 않음, {@code POST /me/release-notes/seen} 200 null·401·404.
 */
@WebMvcTest(ReleaseNoteController.class)
@Import(WebMvcTestSupport.class)
class ReleaseNoteControllerTest {

    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private ReleaseNoteQueryService queryService;
    @MockitoBean
    private ReleaseNoteSeenService seenService;

    @Test
    void listUsesLangParamOrAcceptLanguage() throws Exception {
        ReleaseNoteSummary summary = new ReleaseNoteSummary("1.2.0", "새 기능", LocalDate.of(2026, 10, 6), T, "ko");
        when(queryService.list(anyString())).thenReturn(new ReleaseNoteListResponse(List.of(summary), summary));

        mvc.perform(get("/api/v1/release-notes").param("lang", "ja"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[0].version").value("1.2.0"))
                .andExpect(jsonPath("$.result.items[0].releaseDate").value("2026-10-06"))
                .andExpect(jsonPath("$.result.items[0].lang").value("ko"))
                .andExpect(jsonPath("$.result.portalCard.title").value("새 기능"));
        verify(queryService).list("ja");
        mvc.perform(get("/api/v1/release-notes").header("Accept-Language", "zh-CN,zh;q=0.9"))
                .andExpect(status().isOk());
        verify(queryService).list("zh-CN");
        mvc.perform(get("/api/v1/release-notes").param("lang", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("lang"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("INVALID"));
    }

    @Test
    void detailAndRevisions() throws Exception {
        ReleaseNoteDetailResponse detail = new ReleaseNoteDetailResponse("1.2.0", LocalDate.of(2026, 10, 6), T, T,
                "ja", "en", "New", "<h2 id=\"new\">New</h2>", List.of(new TocEntry(2, "New", "new")),
                new VersionRef("1.1.3", "Old"), null, 3, 4);
        when(queryService.detail("1.2.0", "ko")).thenReturn(detail);
        when(queryService.revisions("1.2.0")).thenReturn(List.of(new ReleaseNoteRevisionItem(4, T)));
        when(queryService.revision("1.2.0", 2, "ko")).thenReturn(detail);

        mvc.perform(get("/api/v1/release-notes/1.2.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.requestedLang").value("ja"))
                .andExpect(jsonPath("$.result.lang").value("en"))
                .andExpect(jsonPath("$.result.contentHtml").value("<h2 id=\"new\">New</h2>"))
                .andExpect(jsonPath("$.result.toc[0].anchor").value("new"))
                .andExpect(jsonPath("$.result.prev.version").value("1.1.3"))
                .andExpect(jsonPath("$.result.next").value(nullValue()))
                .andExpect(jsonPath("$.result.revisionCount").value(3));
        mvc.perform(get("/api/v1/release-notes/1.2.0/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].revisionNo").value(4))
                .andExpect(jsonPath("$.result[0].editedBy").doesNotExist());
        mvc.perform(get("/api/v1/release-notes/1.2.0/revisions/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.revisionNo").value(4));

        when(queryService.detail("9.9.9", "ko"))
                .thenThrow(new BusinessException(ErrorCode.RELEASE_NOTE_NOT_FOUND, "none"));
        mvc.perform(get("/api/v1/release-notes/9.9.9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("RELEASE_NOTE_NOT_FOUND"));
    }

    @Test
    void malformedVersionIsNotMappedAndSearchIsSeparate() throws Exception {
        mvc.perform(get("/api/v1/release-notes/v1.2.0")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/release-notes/latest")).andExpect(status().isNotFound());
        verifyNoInteractions(queryService);

        when(queryService.search(eq("기능"), eq("ko"), any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(new ReleaseNoteSearchHit("1.2.0", "새 기능", "…기능…", LocalDate.of(2026, 10, 6), "ko")),
                PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/v1/release-notes/search").param("q", "기능").param("lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.result[0].snippet").value("…기능…"));
    }

    @Test
    void seenNeedsLoginAndReturnsNull() throws Exception {
        mvc.perform(post("/api/v1/me/release-notes/seen").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.2.0\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/release-notes/seen").cookie(authCookies.user(7L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.2.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(seenService).markSeen(7L, "1.2.0");

        when(seenService.markSeen(7L, "9.9.9"))
                .thenThrow(new BusinessException(ErrorCode.RELEASE_NOTE_NOT_FOUND, "none"));
        mvc.perform(post("/api/v1/me/release-notes/seen").cookie(authCookies.user(7L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"9.9.9\"}"))
                .andExpect(status().isNotFound());
    }
}
