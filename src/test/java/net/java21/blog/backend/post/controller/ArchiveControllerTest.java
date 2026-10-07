package net.java21.blog.backend.post.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.dto.ArchiveMonthResponse;
import net.java21.blog.backend.post.service.ArchiveService;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 보관함 API(T051): 비로그인 200, 없는 블로그 404. */
@WebMvcTest(ArchiveController.class)
@Import(WebMvcTestSupport.class)
class ArchiveControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ArchiveService archiveService;

    @Test
    void anonymousGetsMonths() throws Exception {
        when(archiveService.archive("marco")).thenReturn(List.of(new ArchiveMonthResponse(2026, 10, 3)));
        mvc.perform(get("/api/v1/blogs/marco/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].year").value(2026))
                .andExpect(jsonPath("$.result[0].month").value(10))
                .andExpect(jsonPath("$.result[0].postCount").value(3));
    }

    @Test
    void missingBlogIs404() throws Exception {
        when(archiveService.archive("ghost")).thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));
        mvc.perform(get("/api/v1/blogs/ghost/archive"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BLOG_NOT_FOUND"));
    }
}
