package net.java21.blog.backend.export.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.export.repository.ExportPostQueryRepository;
import net.java21.blog.backend.export.repository.ExportPostQueryRepository.ExportImage;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.media.storage.MediaStorage;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 블로그 백업 zip을 쓴다(004 FR-145, research B14). 새 의존성 없이 {@link ZipOutputStream}과 직접 쓴 YAML front matter를 쓴다.
 * <ul>
 *   <li>{@code blog.json}: 형식 버전·만든 시각·블로그 주소·제목·소개·카테고리 트리</li>
 *   <li>{@code posts/{id}.md}: 휴지통을 뺀 모든 글. front matter({@code title}, {@code category}("상위/하위"), {@code tags},
 *       {@code visibility}, {@code status}, {@code publishedAt}, {@code scheduledAt}, {@code notice}, {@code topic}) + Markdown
 *       원문. 발행 전 글(DRAFT)은 작성 중 사본의 내용</li>
 *   <li>{@code posts/{id}.draft.md}: 발행된(또는 예약된) 글에 작성 중 사본이 있을 때</li>
 *   <li>{@code images/{mediaKey}.{ext}}: 글이 참조하는 원본 이미지(썸네일 제외) 중 블로그 주인이 올린 것. 파일이 없으면 건너뛰고 경고</li>
 *   <li>{@code media.json}: 본문의 {@code /media/{key}} → zip 안의 {@code images/...}</li>
 * </ul>
 * 보호 글 비밀번호, 댓글·방명록, 다른 회원의 정보는 넣지 않는다. 글은 {@link #BATCH_SIZE}편씩(쿼리 3회) 읽고 묶음마다 영속성 컨텍스트를
 * 비워 메모리를 일정하게 둔다. 호출하는 쪽이 읽기 전용 트랜잭션을 연다.
 */
@Component
public class BlogExportWriter {

    /** zip 형식 버전(blog.json {@code formatVersion}). */
    public static final int FORMAT_VERSION = 1;
    static final int BATCH_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(BlogExportWriter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,5}");
    private static final Map<String, String> MIME_EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/gif", "gif", "image/webp", "webp");

    private final ExportPostQueryRepository repository;
    private final TagQueryRepository tagQueryRepository;
    private final MediaStorage mediaStorage;
    private final EntityManager em;
    private final Clock clock;

    public BlogExportWriter(ExportPostQueryRepository repository, TagQueryRepository tagQueryRepository,
            MediaStorage mediaStorage, EntityManager em, Clock clock) {
        this.repository = repository;
        this.tagQueryRepository = tagQueryRepository;
        this.mediaStorage = mediaStorage;
        this.em = em;
        this.clock = clock;
    }

    /** 쓴 결과(글 수, 넣은 이미지 수, 파일이 없어 건너뛴 이미지 수). */
    public record Summary(int posts, int images, int missingImages) {
    }

    /** {@code blog}의 백업을 {@code out}에 zip으로 쓴다. {@code out}은 닫지 않는다(zip 끝은 쓴다). */
    public Summary write(Blog blog, OutputStream out) throws IOException {
        Long blogId = blog.getId();
        Long ownerId = blog.getUser().getId();
        List<Category> categories = repository.findCategories(blogId);
        Map<String, Object> blogJson = blogJson(blog, categories);
        Map<Long, String> categoryPaths = categoryPaths(categories);
        Map<Long, String> topicSlugs = repository.findTopicSlugs();

        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        put(zip, "blog.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(blogJson));

        int posts = 0;
        long afterId = 0;
        while (true) {
            List<Post> batch = repository.findPostBatch(blogId, afterId, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            List<Long> ids = batch.stream().map(Post::getId).toList();
            Map<Long, List<String>> tags = tagQueryRepository.findTagNames(ids);
            Map<Long, PostDraft> drafts = repository.findDrafts(ids);
            for (Post post : batch) {
                writePost(zip, post, tags.getOrDefault(post.getId(), List.of()), drafts.get(post.getId()),
                        categoryPaths, topicSlugs);
                posts++;
            }
            afterId = ids.getLast();
            em.clear();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }

        Map<String, String> mediaMap = new LinkedHashMap<>();
        int missing = 0;
        for (ExportImage image : repository.findImages(blogId, ownerId)) {
            String entry = "images/" + image.mediaKey() + "." + extension(image);
            Resource file = mediaStorage.open(image.status() == MediaStatus.TEMP ? MediaStorage.Area.TEMP
                    : MediaStorage.Area.UPLOAD, image.storedPath());
            if (!file.exists()) {
                log.warn("Export skipped a missing image: blogId={}, mediaKey={}", blogId, image.mediaKey());
                missing++;
                continue;
            }
            zip.putNextEntry(new ZipEntry(entry));
            try (InputStream in = file.getInputStream()) {
                in.transferTo(zip);
            }
            zip.closeEntry();
            mediaMap.put("/media/" + image.mediaKey(), entry);
        }
        put(zip, "media.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(mediaMap));
        zip.finish();
        zip.flush();
        return new Summary(posts, mediaMap.size(), missing);
    }

    private void writePost(ZipOutputStream zip, Post post, List<String> tags, PostDraft draft,
            Map<Long, String> categoryPaths, Map<Long, String> topicSlugs) throws IOException {
        Long categoryId = post.getCategory() == null ? null : post.getCategory().getId();
        boolean unpublishedDraft = post.getStatus() == PostStatus.DRAFT && draft != null;
        if (unpublishedDraft) {
            put(zip, "posts/" + post.getId() + ".md", markdown(draft.getTitle(), categoryPaths.get(draft.getCategoryId()),
                    draft.getTags(), post, topicSlugs.get(draft.getTopicId()), draft.getContentMarkdown()));
            return;
        }
        put(zip, "posts/" + post.getId() + ".md", markdown(post.getTitle(), categoryPaths.get(categoryId), tags, post,
                topicSlugs.get(post.getTopicId()), post.getContentMarkdown()));
        if (draft != null) {
            put(zip, "posts/" + post.getId() + ".draft.md", markdown(draft.getTitle(),
                    categoryPaths.get(draft.getCategoryId()), draft.getTags(), post, topicSlugs.get(draft.getTopicId()),
                    draft.getContentMarkdown()));
        }
    }

    /** YAML front matter + Markdown 원문. 문자열은 모두 큰따옴표로 감싸 이스케이프한다. */
    static String markdown(String title, String categoryPath, List<String> tags, Post post, String topicSlug,
            String body) {
        StringBuilder md = new StringBuilder("---\n");
        md.append("title: ").append(quote(title)).append('\n');
        md.append("category: ").append(categoryPath == null ? "null" : quote(categoryPath)).append('\n');
        md.append("tags: [");
        List<String> safeTags = tags == null ? List.of() : tags;
        for (int i = 0; i < safeTags.size(); i++) {
            md.append(i == 0 ? "" : ", ").append(quote(safeTags.get(i)));
        }
        md.append("]\n");
        md.append("visibility: ").append(post.getVisibility()).append('\n');
        md.append("status: ").append(post.getStatus()).append('\n');
        md.append("publishedAt: ").append(instant(post.getPublishedAt())).append('\n');
        md.append("scheduledAt: ").append(instant(post.getScheduledAt())).append('\n');
        md.append("notice: ").append(post.isNotice()).append('\n');
        md.append("topic: ").append(topicSlug == null ? "null" : quote(topicSlug)).append('\n');
        md.append("---\n\n");
        if (body != null) {
            md.append(body);
            if (!body.endsWith("\n")) {
                md.append('\n');
            }
        }
        return md.toString();
    }

    /** YAML 큰따옴표 문자열: {@code \}, {@code "}, 줄바꿈·탭과 그 밖의 제어 문자를 이스케이프한다. */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String instant(Instant value) {
        return value == null ? "null" : value.toString();
    }

    static String extension(ExportImage image) {
        String name = image.storedName();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (SAFE_EXTENSION.matcher(ext).matches()) {
                return ext;
            }
        }
        return MIME_EXTENSIONS.getOrDefault(image.mime(), "bin");
    }

    private Map<String, Object> blogJson(Blog blog, List<Category> categories) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("formatVersion", FORMAT_VERSION);
        json.put("exportedAt", clock.instant().toString());
        json.put("handle", blog.getHandle());
        json.put("title", blog.getTitle());
        json.put("description", blog.getDescription());
        json.put("categories", categoryTree(categories));
        return json;
    }

    /** 카테고리 트리(최상위 → 하위, 각각 정렬 순서). */
    static List<Map<String, Object>> categoryTree(List<Category> categories) {
        Map<Long, List<Map<String, Object>>> children = new HashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Category c : categories) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", c.getId());
            node.put("name", c.getName());
            node.put("sortOrder", c.getSortOrder());
            node.put("children", children.computeIfAbsent(c.getId(), id -> new ArrayList<>()));
            Long parentId = c.getParent() == null ? null : c.getParent().getId();
            if (parentId == null) {
                roots.add(node);
            } else {
                children.computeIfAbsent(parentId, id -> new ArrayList<>()).add(node);
            }
        }
        return roots;
    }

    /** 카테고리 id → "상위/하위"(2단계). */
    static Map<Long, String> categoryPaths(List<Category> categories) {
        Map<Long, String> names = new HashMap<>();
        Map<Long, Long> parents = new HashMap<>();
        for (Category c : categories) {
            names.put(c.getId(), c.getName());
            parents.put(c.getId(), c.getParent() == null ? null : c.getParent().getId());
        }
        Map<Long, String> paths = new HashMap<>();
        for (Category c : categories) {
            Long parentId = parents.get(c.getId());
            paths.put(c.getId(), parentId == null || !names.containsKey(parentId) ? c.getName()
                    : names.get(parentId) + "/" + c.getName());
        }
        return paths;
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        put(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
