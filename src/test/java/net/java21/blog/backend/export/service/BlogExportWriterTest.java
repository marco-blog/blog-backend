package net.java21.blog.backend.export.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.export.repository.ExportPostQueryRepository;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.PostMedia;
import net.java21.blog.backend.media.domain.PostMediaSource;
import net.java21.blog.backend.media.storage.MediaStorage;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 백업 zip 구성(T101, FR-145, research B14): blog.json(카테고리 트리·형식 버전), 휴지통을 뺀 모든 글의 front matter와 원문,
 * 발행 전 글은 사본 내용, 발행된 글의 사본 .draft.md, 보호 글 비밀번호 없음, 주인 이미지 원본과 media.json(파일이 없으면 건너뛰고
 * 경고, 다른 회원 이미지·참조하지 않는 이미지 제외). 글 묶음마다 쿼리 3회.
 */
@JpaRepositoryTest
@ExtendWith(OutputCaptureExtension.class)
@Import({ExportPostQueryRepository.class, TagQueryRepository.class})
class BlogExportWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private ExportPostQueryRepository repository;
    @Autowired
    private TagQueryRepository tagQueryRepository;
    @Autowired
    private QueryCounter queryCounter;

    @TempDir
    Path uploadDir;

    private JpaFixtures fx;
    private BlogExportWriter writer;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        writer = new BlogExportWriter(repository, tagQueryRepository, new DirectoryStorage(uploadDir), em,
                new MutableClock(NOW));
        owner = fx.user("백업러");
        blog = fx.blog(owner, "exporter");
    }

    @Test
    void zipHasBlogJsonPostsDraftsImagesAndMediaMap(CapturedOutput output) throws Exception {
        Category java = fx.category(blog, null, "Java", 0);
        Category spring = fx.category(blog, java, "Spring", 0);
        Post published = fx.published(blog, "제목: \"따옴표\"\n둘째 줄", spring, 1);
        published.changeNotice(true);
        fx.tagPost(published, fx.tag("spring"), fx.tag("java"));
        PostDraft copy = new PostDraft(published);
        copy.write("고치는 중", "사본 본문", java.getId(), List.of("draft-tag"), NOW);
        em.persist(copy);

        Post locked = new Post(blog, "보호 글");
        locked.publish("보호 글", "보호 본문", "<p>보호 본문</p>", "보호 본문", "요약", null, PostVisibility.PROTECTED, true,
                NOW);
        locked.applyProtection(JpaFixtures.PROTECTED_HASH);
        em.persist(locked);

        Post draft = new Post(blog, "임시");
        em.persist(draft);
        PostDraft draftCopy = new PostDraft(draft);
        draftCopy.write("임시 사본 제목", "임시 사본 본문", null, List.of(), NOW);
        em.persist(draftCopy);

        Post scheduled = fx.scheduled(blog, "예약", PostVisibility.PUBLIC, NOW.plusSeconds(3600));
        Post trashed = fx.published(blog, "휴지통", null, 2);
        trashed.moveToTrash(NOW);

        Media image = media(owner, "k3Jd9fQ2xLmA7pZ0bR5tYw", "photo.PNG", "image/png", true);
        Media missing = media(owner, "missingKeyAAAAAAAAAAAA", "gone.jpg", "image/jpeg", false);
        Media foreign = media(fx.user("남"), "foreignKeyAAAAAAAAAAAA", "x.png", "image/png", true);
        media(owner, "unusedKeyAAAAAAAAAAAAA", "u.png", "image/png", true);
        Media trashedOnly = media(owner, "trashKeyAAAAAAAAAAAAAA", "t.webp", "image/webp", true);
        link(published, image, PostMediaSource.PUBLISHED);
        link(published, missing, PostMediaSource.PUBLISHED);
        link(published, foreign, PostMediaSource.DRAFT);
        link(trashed, trashedOnly, PostMediaSource.PUBLISHED);
        fx.flushAndClear();

        Blog reloaded = em.find(Blog.class, blog.getId());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        queryCounter.reset();
        BlogExportWriter.Summary summary = writer.write(reloaded, out);
        assertThat(queryCounter.count()).as("카테고리·주제·글 묶음 3회·이미지").isEqualTo(6);

        Map<String, byte[]> zip = unzip(out.toByteArray());
        assertThat(summary).isEqualTo(new BlogExportWriter.Summary(4, 1, 1));
        assertThat(zip.keySet()).containsExactlyInAnyOrder("blog.json", "posts/" + published.getId() + ".md",
                "posts/" + published.getId() + ".draft.md", "posts/" + locked.getId() + ".md",
                "posts/" + draft.getId() + ".md", "posts/" + scheduled.getId() + ".md",
                "images/k3Jd9fQ2xLmA7pZ0bR5tYw.png", "media.json");

        JsonNode blogJson = JsonMapper.builder().build().readTree(zip.get("blog.json"));
        assertThat(blogJson.get("formatVersion").asInt()).isEqualTo(1);
        assertThat(blogJson.get("handle").asString()).isEqualTo("exporter");
        assertThat(blogJson.get("exportedAt").asString()).isEqualTo(NOW.toString());
        assertThat(blogJson.get("categories").get(0).get("name").asString()).isEqualTo("Java");
        assertThat(blogJson.get("categories").get(0).get("children").get(0).get("name").asString()).isEqualTo("Spring");

        String md = text(zip, "posts/" + published.getId() + ".md");
        assertThat(md).startsWith("---\ntitle: \"제목: \\\"따옴표\\\"\\n둘째 줄\"\n")
                .contains("category: \"Java/Spring\"\n", "tags: [\"java\", \"spring\"]\n", "visibility: PUBLIC\n",
                        "status: PUBLISHED\n", "scheduledAt: null\n", "notice: true\n", "topic: null\n")
                .endsWith("---\n\n본문\n");
        assertThat(text(zip, "posts/" + published.getId() + ".draft.md"))
                .contains("title: \"고치는 중\"", "category: \"Java\"", "tags: [\"draft-tag\"]")
                .endsWith("사본 본문\n");
        assertThat(text(zip, "posts/" + draft.getId() + ".md")).contains("title: \"임시 사본 제목\"", "status: DRAFT")
                .endsWith("임시 사본 본문\n");
        assertThat(text(zip, "posts/" + scheduled.getId() + ".md"))
                .contains("status: SCHEDULED", "scheduledAt: " + NOW.plusSeconds(3600));
        String lockedMd = text(zip, "posts/" + locked.getId() + ".md");
        assertThat(lockedMd).contains("visibility: PROTECTED").doesNotContain("$2a$");
        assertThat(zip.values()).noneMatch(bytes -> new String(bytes, StandardCharsets.UTF_8)
                .contains(JpaFixtures.PROTECTED_HASH));

        assertThat(zip.get("images/k3Jd9fQ2xLmA7pZ0bR5tYw.png")).isEqualTo("PNGDATA".getBytes(StandardCharsets.UTF_8));
        JsonNode mediaJson = JsonMapper.builder().build().readTree(zip.get("media.json"));
        assertThat(mediaJson.get("/media/k3Jd9fQ2xLmA7pZ0bR5tYw").asString())
                .isEqualTo("images/k3Jd9fQ2xLmA7pZ0bR5tYw.png");
        assertThat(mediaJson.size()).isEqualTo(1);
        assertThat(output).contains("Export skipped a missing image").contains("missingKeyAAAAAAAAAAAA");
    }

    @Test
    void emptyBlogStillHasBlogJsonAndMediaJson() throws Exception {
        fx.flushAndClear();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThat(writer.write(em.find(Blog.class, blog.getId()), out))
                .isEqualTo(new BlogExportWriter.Summary(0, 0, 0));
        assertThat(unzip(out.toByteArray()).keySet()).containsExactly("blog.json", "media.json");
    }

    @Test
    void yamlQuotingAndExtensions() {
        assertThat(BlogExportWriter.quote("a\\b\"c\td\re\u0001")).isEqualTo("\"a\\\\b\\\"c\\td\\re\\x01\"");
        assertThat(BlogExportWriter.quote(null)).isEqualTo("null");
        assertThat(BlogExportWriter.extension(new ExportPostQueryRepository.ExportImage("k", "p", "a.JPEG",
                "image/jpeg", null))).isEqualTo("jpeg");
        assertThat(BlogExportWriter.extension(new ExportPostQueryRepository.ExportImage("k", "p", "noext",
                "image/webp", null))).isEqualTo("webp");
        assertThat(BlogExportWriter.extension(new ExportPostQueryRepository.ExportImage("k", "p", "weird.x-y",
                "application/octet-stream", null))).isEqualTo("bin");
    }

    private Media media(User mediaOwner, String key, String storedName, String mime, boolean withFile)
            throws IOException {
        Media m = new Media(mediaOwner, key, MediaPurpose.POST, storedName, "tmp/" + storedName, mime, 7, 1, 1);
        String relative = "2026/10/" + key + "-" + storedName;
        m.attachAt(relative);
        em.persist(m);
        if (withFile) {
            Path file = uploadDir.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "PNGDATA");
        }
        return m;
    }

    private void link(Post post, Media m, PostMediaSource source) {
        em.persist(new PostMedia(post.getId(), m.getId(), source, NOW));
    }

    private static String text(Map<String, byte[]> zip, String name) {
        return new String(zip.get(name), StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    /** 정식 영역만 쓰는 테스트용 보관소(읽기만). */
    private record DirectoryStorage(Path base) implements MediaStorage {

        @Override
        public String saveTemp(InputStream content, String storedName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String promote(String tempPath, YearMonth month) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void demote(String uploadPath, String tempPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Resource open(Area area, String path) {
            return new FileSystemResource(base.resolve(path));
        }

        @Override
        public boolean delete(Area area, String path) {
            throw new UnsupportedOperationException();
        }
    }
}
