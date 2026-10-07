package net.java21.blog.backend.admin.content;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.moderation.ContentHideService;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.dto.TargetKey;
import net.java21.blog.backend.report.repository.ReportTargetPreviewRepository;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 005 T041: 콘텐츠 숨김 PUT·DELETE(경로 조각 → 종류, 모르는 조각 404), 숨긴 글 목록. */
@WebMvcTest(AdminContentController.class)
@Import(WebMvcTestSupport.class)
class AdminContentControllerTest {

    private static final long ADMIN = 5L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;
    @MockitoBean
    private ContentHideService hideService;
    @MockitoBean
    private HiddenPostQueryRepository hiddenPosts;
    @MockitoBean
    private ReportTargetPreviewRepository previews;

    @BeforeEach
    void setUp() {
        when(roleLookup.isActiveAdmin(ADMIN)).thenReturn(true);
    }

    @ParameterizedTest
    @CsvSource({"posts,POST", "comments,COMMENT", "guestbook-entries,GUESTBOOK", "trackbacks,TRACKBACK"})
    void segmentsMapToTypes(String segment, ReportTargetType type) throws Exception {
        when(hideService.hide(eq(ADMIN), eq(type), eq(7L), eq("광고"), anyString())).thenReturn(preview(type, 7L,
                ReportTargetPreview.State.HIDDEN));
        mvc.perform(put("/api/v1/admin/contents/" + segment + "/7/hidden").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"광고\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.state").value("HIDDEN"))
                .andExpect(jsonPath("$.result.type").value(type.name()));

        when(hideService.unhide(eq(ADMIN), eq(type), eq(7L), isNull(), anyString())).thenReturn(preview(type, 7L,
                ReportTargetPreview.State.ACTIVE));
        mvc.perform(delete("/api/v1/admin/contents/" + segment + "/7/hidden").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.state").value("ACTIVE"));
    }

    @Test
    void unknownSegmentIs404() throws Exception {
        mvc.perform(put("/api/v1/admin/contents/blogs/7/hidden").cookie(authCookies.user(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
        mvc.perform(put("/api/v1/admin/contents/posts/7/hidden").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk());
        verifyNoInteractions(hiddenPosts);
    }

    @Test
    void hiddenPostsKeepTheirOrder() throws Exception {
        when(hiddenPosts.findHiddenPostIds(any())).thenReturn(new PageImpl<>(List.of(8L, 3L), PageRequest.of(0, 20),
                2));
        TargetKey eight = new TargetKey(ReportTargetType.POST, 8L);
        TargetKey three = new TargetKey(ReportTargetType.POST, 3L);
        when(previews.previews(List.of(eight, three))).thenReturn(Map.of(three,
                preview(ReportTargetType.POST, 3L, ReportTargetPreview.State.HIDDEN), eight,
                preview(ReportTargetType.POST, 8L, ReportTargetPreview.State.HIDDEN)));
        mvc.perform(get("/api/v1/admin/contents/hidden-posts").cookie(authCookies.user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(8))
                .andExpect(jsonPath("$.result[1].id").value(3))
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    private static ReportTargetPreview preview(ReportTargetType type, long id, ReportTargetPreview.State state) {
        return new ReportTargetPreview(type, id, state, "t", null, "https://blog.java21.net/marco/" + id, null,
                new ReportTargetPreview.BlogRef("marco", "M"));
    }
}
