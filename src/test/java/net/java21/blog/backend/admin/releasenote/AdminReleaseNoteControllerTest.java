package net.java21.blog.backend.admin.releasenote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.admin.releasenote.dto.AdminReleaseNoteResponse;
import net.java21.blog.backend.admin.releasenote.dto.AdminReleaseNoteSummary;
import net.java21.blog.backend.admin.releasenote.dto.AdminRevisionResponse;
import net.java21.blog.backend.admin.releasenote.dto.ContentWrite;
import net.java21.blog.backend.admin.releasenote.dto.PreviewResponse;
import net.java21.blog.backend.admin.releasenote.dto.ReleaseNoteWriteRequest;
import net.java21.blog.backend.admin.releasenote.dto.UpdateReleaseNoteRequest;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;
import net.java21.blog.backend.releasenote.domain.TocEntry;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 릴리스 노트 관리 API 9개(003 T111): 201 + Location, DELETE 200 null, 409·422 코드, 일반 회원 404. */
@WebMvcTest(AdminReleaseNoteController.class)
@Import(WebMvcTestSupport.class)
class AdminReleaseNoteControllerTest {

    private static final long ADMIN = 5L;
    private static final Instant T = Instant.parse("2026-10-06T00:00:00Z");
    private static final String BODY = """
            {"version":"1.2.0","releaseDate":"2026-10-06","contents":{"ko":{"title":"새 기능","contentMarkdown":"## 새"}}}""";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private AdminReleaseNoteService service;

    private final AdminReleaseNoteResponse note = new AdminReleaseNoteResponse(11L, "1.2.0",
            LocalDate.of(2026, 10, 6), Map.of("ko", new ContentWrite("새 기능", "## 새")), ReleaseNoteStatus.DRAFT, 1,
            null, null, new AdminRef(ADMIN, "관리자"), new AdminRef(ADMIN, "관리자"), T, T);

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @Test
    void membersSeeNothing() throws Exception {
        mvc.perform(get("/api/v1/admin/release-notes").cookie(authCookies.user(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
        verifyNoInteractions(service);
    }

    @Test
    void listCreateGet() throws Exception {
        when(service.list(eq("DRAFT"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                new AdminReleaseNoteSummary(11L, "1.2.0", ReleaseNoteStatus.DRAFT, LocalDate.of(2026, 10, 6),
                        List.of("ko"), 1, null, null, T)), PageRequest.of(0, 20), 1));
        when(service.create(eq(ADMIN), any(), anyString())).thenReturn(note);
        when(service.get(11L)).thenReturn(note);

        mvc.perform(admin(get("/api/v1/admin/release-notes")).param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.result[0].langs[0]").value("ko"));
        mvc.perform(admin(post("/api/v1/admin/release-notes")).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/release-notes/11"))
                .andExpect(jsonPath("$.result.status").value("DRAFT"))
                .andExpect(jsonPath("$.result.revisionNo").value(1))
                .andExpect(jsonPath("$.result.contents.ko.title").value("새 기능"))
                .andExpect(jsonPath("$.result.createdBy.nickname").value("관리자"));
        ArgumentCaptor<ReleaseNoteWriteRequest> request = ArgumentCaptor.forClass(ReleaseNoteWriteRequest.class);
        verify(service).create(eq(ADMIN), request.capture(), eq("127.0.0.1"));
        assertThat(request.getValue().releaseDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(request.getValue().contents().get("ko").contentMarkdown()).isEqualTo("## 새");
        mvc.perform(admin(get("/api/v1/admin/release-notes/11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(11));
    }

    @Test
    void updatePublishUnpublishDelete() throws Exception {
        when(service.update(eq(ADMIN), eq(11L), any(), anyString())).thenReturn(note);
        when(service.publish(ADMIN, 11L, "127.0.0.1")).thenReturn(note);
        when(service.unpublish(ADMIN, 11L, "127.0.0.1")).thenReturn(note);

        mvc.perform(admin(put("/api/v1/admin/release-notes/11")).content(BODY.replace("}}}", "}},\"baseRevisionNo\":3}")))
                .andExpect(status().isOk());
        ArgumentCaptor<UpdateReleaseNoteRequest> request = ArgumentCaptor.forClass(UpdateReleaseNoteRequest.class);
        verify(service).update(eq(ADMIN), eq(11L), request.capture(), anyString());
        assertThat(request.getValue().baseRevisionNo()).isEqualTo(3);
        mvc.perform(admin(post("/api/v1/admin/release-notes/11/publish"))).andExpect(status().isOk());
        mvc.perform(admin(post("/api/v1/admin/release-notes/11/unpublish"))).andExpect(status().isOk());
        mvc.perform(admin(delete("/api/v1/admin/release-notes/11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(service).delete(ADMIN, 11L, "127.0.0.1");
    }

    @Test
    void previewAndRevisions() throws Exception {
        when(service.preview("## 새")).thenReturn(new PreviewResponse("<h2 id=\"새\">새</h2>",
                List.of(new TocEntry(2, "새", "새"))));
        when(service.revisions(11L)).thenReturn(List.of(new AdminRevisionResponse(2, new AdminRef(ADMIN, "관리자"), T,
                ReleaseNoteStatus.PUBLISHED, null, null, null)));
        when(service.revision(11L, 1)).thenReturn(new AdminRevisionResponse(1, new AdminRef(ADMIN, "관리자"), T,
                ReleaseNoteStatus.DRAFT, "1.2.0", LocalDate.of(2026, 10, 6), Map.of("ko",
                        new ContentWrite("t", "b"))));

        mvc.perform(admin(post("/api/v1/admin/release-notes/preview")).content("{\"contentMarkdown\":\"## 새\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.toc[0].anchor").value("새"));
        mvc.perform(admin(get("/api/v1/admin/release-notes/11/revisions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].editedBy.nickname").value("관리자"))
                .andExpect(jsonPath("$.result[0].contents").doesNotExist());
        mvc.perform(admin(get("/api/v1/admin/release-notes/11/revisions/1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contents.ko.title").value("t"))
                .andExpect(jsonPath("$.result.version").value("1.2.0"));
    }

    @Test
    void errorCodes() throws Exception {
        when(service.create(eq(ADMIN), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.RELEASE_NOTE_VERSION_TAKEN, "taken"));
        when(service.update(eq(ADMIN), anyLong(), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.RELEASE_NOTE_REVISION_CONFLICT, "conflict"))
                .thenThrow(new BusinessException(ErrorCode.RELEASE_NOTE_VERSION_LOCKED, "locked"));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.RELEASE_NOTE_ONCE_PUBLISHED, "once"))
                .when(service).delete(ADMIN, 11L, "127.0.0.1");

        mvc.perform(admin(post("/api/v1/admin/release-notes")).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("RELEASE_NOTE_VERSION_TAKEN"));
        mvc.perform(admin(put("/api/v1/admin/release-notes/11")).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("RELEASE_NOTE_REVISION_CONFLICT"));
        mvc.perform(admin(put("/api/v1/admin/release-notes/11")).content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("RELEASE_NOTE_VERSION_LOCKED"));
        mvc.perform(admin(delete("/api/v1/admin/release-notes/11")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("RELEASE_NOTE_ONCE_PUBLISHED"));
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.cookie(authCookies.user(ADMIN)).contentType(MediaType.APPLICATION_JSON);
    }
}
