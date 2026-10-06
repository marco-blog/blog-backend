package net.java21.blog.backend.seo.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.seo.SitemapProperties;
import net.java21.blog.backend.seo.repository.SitemapBlogRow;
import net.java21.blog.backend.seo.repository.SitemapPostRow;
import net.java21.blog.backend.seo.repository.SitemapQueryRepository;
import net.java21.blog.backend.seo.repository.SitemapStats;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사이트맵(002 FR-037, contracts/api.md 사이트맵 절, research D5). 주소는 모두 {@code blog.base-url} 기준 절대 주소.
 * <ul>
 *   <li>색인: {@code /sitemap/pages.xml}과 {@code /sitemap/posts-1.xml} … {@code posts-N.xml}
 *       (N = ceil(본문 노출 가능 글 수 / {@code urls-per-file}), 0이면 posts 파일 없음). 쿼리 1회.</li>
 *   <li>pages: {@code /}, {@code /terms}, {@code /privacy}, 본문 노출 가능 글이 있는 블로그 홈, 운영자 숨김이 아닌 주제 페이지
 *       (003 FR-094, {@code lastmod} 없음), 게시된 릴리스 노트 {@code /updates/v{version}}({@code lastmod} = 노트 수정 시각,
 *       003 FR-164). 쿼리 3회.</li>
 *   <li>posts-n: id 순 n번째 묶음. 범위 밖이면 404 {@code NOT_FOUND}. 쿼리 2회(수, 목록).</li>
 * </ul>
 */
@Service
public class SitemapService {

    static final List<String> FIXED_PAGES = List.of("/", "/terms", "/privacy");

    private final SitemapQueryRepository repository;
    private final SitemapWriter writer;
    private final SiteProperties site;
    private final SitemapProperties properties;

    public SitemapService(SitemapQueryRepository repository, SitemapWriter writer, SiteProperties site,
            SitemapProperties properties) {
        this.repository = repository;
        this.writer = writer;
        this.site = site;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public SitemapDocument index() {
        SitemapStats stats = repository.stats();
        List<SitemapWriter.Entry> entries = new ArrayList<>();
        entries.add(new SitemapWriter.Entry(site.url("/sitemap/pages.xml"), null));
        for (int n = 1; n <= fileCount(stats); n++) {
            entries.add(new SitemapWriter.Entry(site.url("/sitemap/posts-" + n + ".xml"), null));
        }
        return new SitemapDocument(writer.index(entries), stats.lastModified());
    }

    @Transactional(readOnly = true)
    public SitemapDocument pages() {
        List<SitemapBlogRow> blogs = repository.findBlogs();
        List<SitemapWriter.Entry> entries = new ArrayList<>();
        FIXED_PAGES.forEach(path -> entries.add(new SitemapWriter.Entry(site.url(path), null)));
        blogs.forEach(b -> entries.add(new SitemapWriter.Entry(site.url("/" + b.handle()), b.lastPublishedAt())));
        repository.findTopicPaths().forEach(path -> entries.add(new SitemapWriter.Entry(site.url(path), null)));
        repository.findReleaseNotes().forEach(note -> entries.add(
                new SitemapWriter.Entry(site.url("/updates/v" + note.version()), note.updatedAt())));
        return new SitemapDocument(writer.urlset(entries), entries.stream().map(SitemapWriter.Entry::lastModified)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
    }

    /** {@code n}은 1부터. */
    @Transactional(readOnly = true)
    public SitemapDocument posts(int n) {
        if (n < 1 || n > fileCount(repository.stats())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Sitemap not found: posts-" + n);
        }
        List<SitemapPostRow> rows = repository.findPosts(n - 1, properties.urlsPerFile());
        List<SitemapWriter.Entry> entries = rows.stream()
                .map(r -> new SitemapWriter.Entry(site.url("/" + r.blogHandle() + "/" + r.id()), r.updatedAt()))
                .toList();
        return new SitemapDocument(writer.urlset(entries), rows.stream().map(SitemapPostRow::updatedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
    }

    private long fileCount(SitemapStats stats) {
        return (stats.postCount() + properties.urlsPerFile() - 1) / properties.urlsPerFile();
    }
}
