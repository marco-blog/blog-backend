package net.java21.blog.backend.category.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import net.java21.blog.backend.category.dto.CategoryNode;
import net.java21.blog.backend.category.dto.CategoryOrderItem;
import net.java21.blog.backend.category.dto.CreateCategoryRequest;
import net.java21.blog.backend.category.dto.UpdateCategoryRequest;
import net.java21.blog.backend.category.service.CategoryService;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 카테고리 API(T170): 조회는 비로그인 가능, 쓰기는 로그인한 주인만(주인 아님 403), 상태 코드·응답 형식. */
@WebMvcTest(CategoryController.class)
@Import(WebMvcTestSupport.class)
class CategoryControllerTest {

    private static final CategoryNode SPRING = new CategoryNode(1L, "Spring", 3,
            List.of(new CategoryNode(2L, "Boot", 2, List.of())));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private CategoryService categoryService;

    @Test
    void treeIsPublic() throws Exception {
        when(categoryService.tree("marco")).thenReturn(List.of(SPRING));

        mvc.perform(get("/api/v1/blogs/marco/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result", hasSize(1)))
                .andExpect(jsonPath("$.result[0].name").value("Spring"))
                .andExpect(jsonPath("$.result[0].postCount").value(3))
                .andExpect(jsonPath("$.result[0].children[0].id").value(2))
                .andExpect(jsonPath("$.result[0].children[0].children", hasSize(0)));
    }

    @Test
    void createIs201WithNode() throws Exception {
        when(categoryService.create(1L, "marco", new CreateCategoryRequest("JPA", 1L)))
                .thenReturn(new CategoryNode(5L, "JPA", 0, List.of()));

        mvc.perform(post("/api/v1/blogs/marco/categories").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"JPA\",\"parentId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/blogs/marco/categories/5"))
                .andExpect(jsonPath("$.result.id").value(5))
                .andExpect(jsonPath("$.result.postCount").value(0));
    }

    @Test
    void errorsUseCommonEnvelope() throws Exception {
        when(categoryService.create(eq(1L), eq("marco"), any()))
                .thenThrow(new BusinessException(ErrorCode.CATEGORY_NAME_TAKEN, "taken"));
        mvc.perform(post("/api/v1/blogs/marco/categories").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Spring\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("CATEGORY_NAME_TAKEN"))
                .andExpect(jsonPath("$.result").doesNotExist());

        when(categoryService.create(eq(2L), eq("marco"), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "not owner"));
        mvc.perform(post("/api/v1/blogs/marco/categories").cookie(authCookies.user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("FORBIDDEN"));

        when(categoryService.rename(eq(1L), eq("marco"), eq(9L), any()))
                .thenThrow(new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "nope"));
        mvc.perform(patch("/api/v1/blogs/marco/categories/9").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CATEGORY_NOT_FOUND"));

        when(categoryService.reorder(eq(1L), eq("marco"), anyList()))
                .thenThrow(new BusinessException(ErrorCode.CATEGORY_DEPTH_EXCEEDED, "deep"));
        mvc.perform(put("/api/v1/blogs/marco/categories/order").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"id\":1,\"parentId\":3,\"sortOrder\":0}]"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.header.resultCode").value("CATEGORY_DEPTH_EXCEEDED"));
    }

    @Test
    void renameReorderAndDelete() throws Exception {
        when(categoryService.rename(1L, "marco", 1L, new UpdateCategoryRequest("Spring 4")))
                .thenReturn(new CategoryNode(1L, "Spring 4", 3, List.of()));
        mvc.perform(patch("/api/v1/blogs/marco/categories/1").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Spring 4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.name").value("Spring 4"));

        List<CategoryOrderItem> items = List.of(new CategoryOrderItem(2L, null, 0), new CategoryOrderItem(1L, null, 1));
        when(categoryService.reorder(1L, "marco", items)).thenReturn(List.of(SPRING));
        mvc.perform(put("/api/v1/blogs/marco/categories/order").cookie(authCookies.user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":2,\"parentId\":null,\"sortOrder\":0},{\"id\":1,\"sortOrder\":1}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(1));

        mvc.perform(delete("/api/v1/blogs/marco/categories/1").cookie(authCookies.user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.nullValue()));
        verify(categoryService).delete(1L, "marco", 1L);

        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "x")).when(categoryService).delete(2L, "marco", 1L);
        mvc.perform(delete("/api/v1/blogs/marco/categories/1").cookie(authCookies.user(2L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void writesNeedLogin() throws Exception {
        mvc.perform(post("/api/v1/blogs/marco/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        mvc.perform(delete("/api/v1/blogs/marco/categories/1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/blogs/marco/categories/order").contentType(MediaType.APPLICATION_JSON)
                .content("[]")).andExpect(status().isUnauthorized());
        verifyNoInteractions(categoryService);
    }
}
