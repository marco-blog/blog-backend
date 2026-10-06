package net.java21.blog.backend.seo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.seo.SitemapProperties;
import net.java21.blog.backend.seo.repository.SitemapBlogRow;
import net.java21.blog.backend.seo.repository.SitemapPostRow;
import net.java21.blog.backend.seo.repository.SitemapQueryRepository;
import net.java21.blog.backend.seo.repository.SitemapStats;
import net.java21.blog.backend.seo.service.SitemapService;
import net.java21.blog.backend.seo.service.SitemapWriter;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 사이트맵·robots(T064, FR-037, research D5). 저장소만 흉내 내고 서비스·StAX 작성기는 실제로 쓴다.
 * 색인(pages + posts-1…N, 글이 없으면 pages만), pages(고정 화면·블로그 홈), posts-n(범위 밖 404), XML 파싱 가능·sitemaps.org
 * 네임스페이스·{@code loc}은 {@code blog.base-url} 절대 주소·{@code lastmod} W3C 날짜, {@code Content-Type: application/xml},
 * {@code Cache-Control: no-cache}; robots 차단 규칙과 {@code Sitemap:} 줄. 비로그인으로 열린다.
 */
@WebMvcTest({SitemapController.class, RobotsController.class})
@Import({WebMvcTestSupport.class, SitemapService.class, SitemapWriter.class,
        SitemapControllerTest.Properties.class})
@TestPropertySource(properties = {"blog.base-url=https://blog.example.com/", "blog.sitemap.urls-per-file=2"})
class SitemapControllerTest {

    private static final String NS = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final Instant UPDATED = Instant.parse("2026-10-06T04:24:19.123456Z");

    @TestConfiguration
    @EnableConfigurationProperties({SiteProperties.class, SitemapProperties.class})
    static class Properties {
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SitemapQueryRepository repository;

    @Test
    void indexListsPagesAndEveryPostsFile() throws Exception {
        when(repository.stats()).thenReturn(new SitemapStats(5, UPDATED));

        MvcResult result = mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/xml;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(header().exists(HttpHeaders.LAST_MODIFIED))
                .andReturn();

        Document doc = parse(result);
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("sitemapindex");
        assertThat(doc.getDocumentElement().getNamespaceURI()).isEqualTo(NS);
        assertThat(texts(doc, "loc")).containsExactly("https://blog.example.com/sitemap/pages.xml",
                "https://blog.example.com/sitemap/posts-1.xml", "https://blog.example.com/sitemap/posts-2.xml",
                "https://blog.example.com/sitemap/posts-3.xml");
    }

    @Test
    void indexWithoutPostsListsOnlyPages() throws Exception {
        when(repository.stats()).thenReturn(new SitemapStats(0, null));

        MvcResult result = mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(texts(parse(result), "loc")).containsExactly("https://blog.example.com/sitemap/pages.xml");
    }

    @Test
    void pagesListFixedScreensAndBlogHomes() throws Exception {
        when(repository.findBlogs()).thenReturn(List.of(new SitemapBlogRow("marco", UPDATED),
                new SitemapBlogRow("third", Instant.parse("2026-10-01T00:00:00Z"))));

        MvcResult result = mvc.perform(get("/sitemap/pages.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/xml;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andReturn();

        Document doc = parse(result);
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("urlset");
        assertThat(doc.getDocumentElement().getNamespaceURI()).isEqualTo(NS);
        assertThat(texts(doc, "loc")).containsExactly("https://blog.example.com/", "https://blog.example.com/terms",
                "https://blog.example.com/privacy", "https://blog.example.com/marco", "https://blog.example.com/third");
        assertThat(texts(doc, "lastmod")).containsExactly("2026-10-06T04:24:19Z", "2026-10-01T00:00:00Z");
        assertThat(doc.getElementsByTagNameNS(NS, "changefreq").getLength()).isZero();
        assertThat(doc.getElementsByTagNameNS(NS, "priority").getLength()).isZero();
    }

    /** 003 T061: pages.xml에 운영자 숨김이 아닌 주제 페이지의 절대 주소(lastmod 없음). */
    @Test
    void pagesIncludeTopicPagesWithoutLastmod() throws Exception {
        when(repository.findBlogs()).thenReturn(List.of(new SitemapBlogRow("marco", UPDATED)));
        when(repository.findTopicPaths()).thenReturn(List.of("/topics/knowledge", "/topics/knowledge/it-internet"));

        Document doc = parse(mvc.perform(get("/sitemap/pages.xml")).andExpect(status().isOk()).andReturn());

        assertThat(texts(doc, "loc")).containsExactly("https://blog.example.com/", "https://blog.example.com/terms",
                "https://blog.example.com/privacy", "https://blog.example.com/marco",
                "https://blog.example.com/topics/knowledge", "https://blog.example.com/topics/knowledge/it-internet");
        assertThat(texts(doc, "lastmod")).containsExactly("2026-10-06T04:24:19Z");
    }

    @Test
    void postsFileListsPostUrlsWithLastmod() throws Exception {
        when(repository.stats()).thenReturn(new SitemapStats(3, UPDATED));
        when(repository.findPosts(1, 2)).thenReturn(List.of(new SitemapPostRow(12L, "marco", UPDATED)));

        MvcResult result = mvc.perform(get("/sitemap/posts-2.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/xml;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(header().exists(HttpHeaders.LAST_MODIFIED))
                .andReturn();

        Document doc = parse(result);
        assertThat(texts(doc, "loc")).containsExactly("https://blog.example.com/marco/12");
        assertThat(texts(doc, "lastmod")).containsExactly("2026-10-06T04:24:19Z");
    }

    @Test
    void postsFileOutOfRangeIs404() throws Exception {
        when(repository.stats()).thenReturn(new SitemapStats(3, UPDATED));

        mvc.perform(get("/sitemap/posts-3.xml"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
        mvc.perform(get("/sitemap/posts-0.xml"))
                .andExpect(status().isNotFound());
        verify(repository, never()).findPosts(anyInt(), anyInt());
    }

    @Test
    void postsFileWhenThereAreNoPostsIs404() throws Exception {
        when(repository.stats()).thenReturn(new SitemapStats(0, null));

        mvc.perform(get("/sitemap/posts-1.xml")).andExpect(status().isNotFound());
    }

    @Test
    void robotsBlocksPrivateScreensAndPointsToTheSitemap() throws Exception {
        mvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("text/plain")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("charset=UTF-8")))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).startsWith("User-agent: *\n");
                    assertThat(body).contains("Disallow: /api/\n", "Disallow: /login$\n", "Disallow: /signup$\n",
                            "Disallow: /password-reset\n", "Disallow: /settings$\n", "Disallow: /settings/\n",
                            "Disallow: /write$\n", "Disallow: /manage$\n", "Disallow: /*/write$\n",
                            "Disallow: /*/write/\n", "Disallow: /*/manage$\n", "Disallow: /*/manage/\n",
                            "Disallow: /feed$\n", "Disallow: /feed?\n", "Disallow: /notifications$\n",
                            "Disallow: /search$\n", "Disallow: /search?\n");
                    assertThat(body).endsWith("Sitemap: https://blog.example.com/sitemap.xml\n");
                    // 블로그 주소가 예약어로 시작해도(예: /feedback) 막지 않는다
                    assertThat(body).doesNotContain("Disallow: /feed\n", "Disallow: /search\n");
                });
    }

    private static Document parse(MvcResult result) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
    }

    private static List<String> texts(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS(NS, localName);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(((Element) nodes.item(i)).getTextContent());
        }
        return values;
    }
}
