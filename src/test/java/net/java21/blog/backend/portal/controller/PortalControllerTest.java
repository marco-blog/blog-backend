package net.java21.blog.backend.portal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.dto.LatestSection;
import net.java21.blog.backend.portal.dto.NewBlogResponse;
import net.java21.blog.backend.portal.dto.PopularTagResponse;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.dto.PortalHomeResponse;
import net.java21.blog.backend.portal.service.PortalService;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 포털 메인 API(003 T038, contracts/api.md 포털 메인 절): 비로그인 200, PortalHome 필드, 최신 글 커서, 잘못된 커서 400. */
@WebMvcTest(PortalController.class)
@Import(WebMvcTestSupport.class)
class PortalControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final PortalCardResponse CARD = new PortalCardResponse(123L, "Spring Boot 4 시작하기", "요약",
            "/media/k3Jd9fQ2xLmA7pZ0bR5tYw", 12L, new PortalCardResponse.BlogRef("marco", "마르코의 블로그"),
            new PortalCardResponse.AuthorRef("마르코", null), NOW, 3, 2);

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private PortalService portalService;

    @Test
    void anonymousGetsPortalHome() throws Exception {
        when(portalService.home()).thenReturn(new PortalHomeResponse(List.of(CARD), List.of(CARD),
                new LatestSection(List.of(CARD), "eyJwIjoxfQ"), List.of(new PopularTagResponse("spring", 14)),
                List.of(new NewBlogResponse("marco", "마르코의 블로그", null, null,
                        new NewBlogResponse.Owner("마르코", "/media/Ab3"), NOW)),
                NOW));

        mvc.perform(get("/api/v1/portal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result.curations[0].id").value(123))
                .andExpect(jsonPath("$.result.popular[0].title").value("Spring Boot 4 시작하기"))
                .andExpect(jsonPath("$.result.popular[0].summary").value("요약"))
                .andExpect(jsonPath("$.result.popular[0].thumbnailUrl").value("/media/k3Jd9fQ2xLmA7pZ0bR5tYw"))
                .andExpect(jsonPath("$.result.popular[0].topicId").value(12))
                .andExpect(jsonPath("$.result.popular[0].blog.handle").value("marco"))
                .andExpect(jsonPath("$.result.popular[0].blog.title").value("마르코의 블로그"))
                .andExpect(jsonPath("$.result.popular[0].author.nickname").value("마르코"))
                .andExpect(jsonPath("$.result.popular[0].publishedAt").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result.popular[0].likeCount").value(3))
                .andExpect(jsonPath("$.result.popular[0].commentCount").value(2))
                .andExpect(jsonPath("$.result.latest.items[0].id").value(123))
                .andExpect(jsonPath("$.result.latest.nextCursor").value("eyJwIjoxfQ"))
                .andExpect(jsonPath("$.result.popularTags[0].name").value("spring"))
                .andExpect(jsonPath("$.result.popularTags[0].postCount").value(14))
                .andExpect(jsonPath("$.result.newBlogs[0].handle").value("marco"))
                .andExpect(jsonPath("$.result.newBlogs[0].owner.profileImageUrl").value("/media/Ab3"))
                .andExpect(jsonPath("$.result.newBlogs[0].firstPublishedAt").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result.generatedAt").value("2026-10-06T04:24:19Z"));
    }

    @Test
    void latestReturnsCursorPage() throws Exception {
        when(portalService.latest("abc", null)).thenReturn(new LatestSection(List.of(CARD), "next"));

        mvc.perform(get("/api/v1/portal/latest").param("cursor", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(123))
                .andExpect(jsonPath("$.nextCursor").value("next"))
                .andExpect(jsonPath("$.totalCount").doesNotExist());
    }

    @Test
    void lastLatestBatchHasNoNextCursor() throws Exception {
        when(portalService.latest(null, null)).thenReturn(new LatestSection(List.of(), null));

        mvc.perform(get("/api/v1/portal/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void malformedCursorIs400() throws Exception {
        when(portalService.latest("bad", null)).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "x",
                List.of(FieldError.of("cursor", "INVALID"))));

        mvc.perform(get("/api/v1/portal/latest").param("cursor", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("cursor"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("INVALID"));
    }

    @Test
    void latestPassesSourceFilterAndShowsExternalCard() throws Exception {
        PortalCardResponse external = PortalCardResponse.external(7L, "외부 글", "요약", null, 12L,
                new PortalCardResponse.ExternalBlogRef(3L, "Dev Log", "dev.example"), CARD.publishedAt());
        when(portalService.latest(null, "external")).thenReturn(new LatestSection(List.of(external), null));

        mvc.perform(get("/api/v1/portal/latest").param("source", "external"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(7))
                .andExpect(jsonPath("$.result[0].source").value("EXTERNAL"))
                .andExpect(jsonPath("$.result[0].visitUrl").value("/api/v1/external-posts/7/visit"))
                .andExpect(jsonPath("$.result[0].externalBlog.siteHost").value("dev.example"))
                .andExpect(jsonPath("$.result[0].blog.handle").doesNotExist())
                .andExpect(jsonPath("$.result[0].blog.title").value("Dev Log"))
                .andExpect(jsonPath("$.result[0].author").doesNotExist())
                .andExpect(jsonPath("$.result[0].likeCount").value(0));
    }

    @Test
    void internalCardKeepsSourceInternal() throws Exception {
        when(portalService.latest(null, null)).thenReturn(new LatestSection(List.of(CARD), null));

        mvc.perform(get("/api/v1/portal/latest"))
                .andExpect(jsonPath("$.result[0].source").value("INTERNAL"))
                .andExpect(jsonPath("$.result[0].visitUrl").doesNotExist())
                .andExpect(jsonPath("$.result[0].externalBlog").doesNotExist());
    }
}
