package net.java21.blog.backend.syndication.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.FeedContentMode;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.syndication.repository.FeedItemQueryRepository;
import net.java21.blog.backend.syndication.repository.FeedItemRow;
import net.java21.blog.backend.syndication.repository.FeedVersionRow;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그·카테고리 피드(002 FR-044~047, contracts/api.md 블로그 피드 절, research D6). 서버 캐시 없이 요청마다 노출 조각으로 읽는다(SC-004).
 * <ol>
 *   <li>{@link #plan}: 블로그(ACTIVE·주인 ACTIVE, 아니면 404 {@code BLOG_NOT_FOUND})와 카테고리(이 블로그의 것, 아니면 404
 *       {@code CATEGORY_NOT_FOUND})를 확인하고 담을 글의 (id, 수정 시각)만 읽어 약한 ETag와 Last-Modified를 만든다.
 *       ETag는 블로그 제목·소개·주인 닉네임·피드 설정·수정 시각, 카테고리 이름·수정 시각, 글 버전 목록, 서비스 주소의 SHA-256이다.
 *       쿼리 2~3회.</li>
 *   <li>{@link #snapshot}: 바뀌었을 때만 본문을 읽는다(글 1회 + 태그 1회). FULL은 본문 HTML(절대 주소), SUMMARY는 요약,
 *       본문 노출 가능이 아닌 글은 제목·링크·시각만.</li>
 * </ol>
 */
@Service
public class FeedService {

    private final BlogAccess blogAccess;
    private final CategoryAccess categoryAccess;
    private final FeedItemQueryRepository itemRepository;
    private final TagQueryRepository tagQueryRepository;
    private final FeedContentUrlRewriter urlRewriter;
    private final SiteProperties site;

    public FeedService(BlogAccess blogAccess, CategoryAccess categoryAccess, FeedItemQueryRepository itemRepository,
            TagQueryRepository tagQueryRepository, FeedContentUrlRewriter urlRewriter, SiteProperties site) {
        this.blogAccess = blogAccess;
        this.categoryAccess = categoryAccess;
        this.itemRepository = itemRepository;
        this.tagQueryRepository = tagQueryRepository;
        this.urlRewriter = urlRewriter;
        this.site = site;
    }

    /** @param categoryId 카테고리 피드면 그 id, 블로그 피드면 null */
    @Transactional(readOnly = true)
    public FeedPlan plan(String handle, Long categoryId) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        Category category = categoryId == null ? null : categoryAccess.requireInBlog(blog.getId(), categoryId);
        FeedSource source = new FeedSource(blog.getId(), blog.getHandle(), blog.getTitle(), blog.getDescription(),
                blog.getUser().getNickname(), blog.getFeedItemCount(), blog.getFeedContentMode(), blog.getUpdatedAt(),
                category == null ? null : category.getId(), category == null ? null : category.getName(),
                category == null ? null : category.getUpdatedAt());
        List<FeedVersionRow> versions = itemRepository.findVersions(source.blogId(), source.categoryId(),
                source.itemCount());
        return new FeedPlan(source, versions, etag(source, versions), lastModified(source, versions));
    }

    @Transactional(readOnly = true)
    public FeedSnapshot snapshot(FeedPlan plan) {
        FeedSource source = plan.source();
        List<FeedItemRow> rows = itemRepository.findItems(source.blogId(), source.categoryId(), source.itemCount());
        Map<Long, List<String>> tags = tagQueryRepository.findTagNames(rows.stream().map(FeedItemRow::id).toList());
        List<FeedEntry> entries = rows.stream().map(row -> entry(source, row, tags.get(row.id()))).toList();
        String blogUrl = site.url("/" + source.handle());
        boolean categoryFeed = source.categoryId() != null;
        String title = categoryFeed ? source.categoryName() + " - " + source.title() : source.title();
        String description = source.description() == null || source.description().isBlank()
                ? source.title() : source.description();
        String link = categoryFeed ? blogUrl + "/category/" + source.categoryId() : blogUrl;
        Instant updated = Stream.concat(Stream.of(plan.lastModified()), rows.stream().map(FeedItemRow::updatedAt))
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new FeedSnapshot(title, link, description, link + "/rss", categoryFeed ? null : blogUrl + "/atom",
                source.ownerNickname(), updated, entries);
    }

    private FeedEntry entry(FeedSource source, FeedItemRow row, List<String> tags) {
        String link = site.url("/" + source.handle() + "/" + row.id());
        if (!row.bodyVisible()) {
            // 004 보호 글: 제목·링크·시각만(FR-047)
            return new FeedEntry(row.title(), link, row.publishedAt(), row.updatedAt(), null, null, List.of());
        }
        List<String> categories = new ArrayList<>();
        if (row.categoryName() != null) {
            categories.add(row.categoryName());
        }
        if (tags != null) {
            categories.addAll(tags);
        }
        boolean full = source.contentMode() == FeedContentMode.FULL;
        return new FeedEntry(row.title(), link, row.publishedAt(), row.updatedAt(),
                full ? urlRewriter.rewrite(row.contentHtml()) : null, full ? null : row.summary(), categories);
    }

    private String etag(FeedSource source, List<FeedVersionRow> versions) {
        StringBuilder input = new StringBuilder()
                .append(site.baseUrl()).append('\n')
                .append(source.blogId()).append('\n')
                .append(source.handle()).append('\n')
                .append(source.title()).append('\n')
                .append(source.description()).append('\n')
                .append(source.ownerNickname()).append('\n')
                .append(source.itemCount()).append('\n')
                .append(source.contentMode()).append('\n')
                .append(source.blogUpdatedAt()).append('\n')
                .append(source.categoryId()).append('\n')
                .append(source.categoryName()).append('\n')
                .append(source.categoryUpdatedAt()).append('\n');
        versions.forEach(v -> input.append(v.id()).append(':').append(v.updatedAt()).append('\n'));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8));
            return "W/\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant lastModified(FeedSource source, List<FeedVersionRow> versions) {
        return Stream.concat(Stream.of(source.blogUpdatedAt(), source.categoryUpdatedAt()),
                        versions.stream().map(FeedVersionRow::updatedAt))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}
