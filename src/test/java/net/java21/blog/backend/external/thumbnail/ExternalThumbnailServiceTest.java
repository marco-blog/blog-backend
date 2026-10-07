package net.java21.blog.backend.external.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.media.MediaProperties;
import net.java21.blog.backend.media.TestImages;
import net.java21.blog.backend.media.service.ImageInspector;
import net.java21.blog.backend.media.service.MediaKeyGenerator;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.ExternalTestKit;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.StubHttpServer;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/** 007 T029: 외부 글 썸네일(FR-128, research E7). 인증된 블로그만, 600x400 cover 한 크기, 원본은 저장하지 않는다. */
@JpaRepositoryTest
class ExternalThumbnailServiceTest {

    private static final Instant NOW = JpaFixtures.T0;

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path root;

    private StubHttpServer server;
    private MutableClock clock;
    private ExternalThumbnailService service;
    private ExternalBlog verified;
    private ExternalBlog unverified;
    private Topic topic;
    private int seq;

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        clock = new MutableClock(NOW);
        JpaFixtures f = new JpaFixtures(em);
        ExternalFixtures x = new ExternalFixtures(em);
        User member = f.user("member");
        topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
        verified = x.blog(member, "https://verified.example/feed", topic, ExternalBlogStatus.ACTIVE);
        verified.markVerified(NOW);
        unverified = x.blog(member, "https://unverified.example/feed", topic, ExternalBlogStatus.ACTIVE);
        service = service(TestImages.properties(root));
    }

    private ExternalThumbnailService service(MediaProperties media) {
        return new ExternalThumbnailService(postRepository, ExternalTestKit.fetcher(server), new ImageInspector(media),
                new MediaKeyGenerator(), media, new TransactionTemplate(transactionManager), clock);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ExternalPost post(ExternalBlog blog, String imagePath, Instant publishedAt) {
        int n = ++seq;
        String link = "https://post.example/" + blog.getId() + "/" + n;
        String image = imagePath == null ? null : server.uri(imagePath).toString();
        FeedItem item = new FeedItem("g" + n, link, "T" + n, "s", image, publishedAt, List.of());
        ExternalPost post = new ExternalPost(blog, item, FeedUrlNormalizer.sha256("g" + n),
                FeedUrlNormalizer.hash(link), topic, TopicSource.DEFAULT, NOW);
        em.persist(post);
        em.flush();
        return post;
    }

    private void image(String path, String mime, byte[] body) {
        server.respondBytes(path, 200, mime, body, Map.of());
    }

    private ExternalPost reload(ExternalPost post) {
        em.flush();
        em.clear();
        return postRepository.findById(post.getId()).orElseThrow();
    }

    @Test
    void makesJpegThumbnailForVerifiedBlog() {
        image("/a.jpg", "image/jpeg", TestImages.jpeg(1200, 900));
        ExternalPost post = post(verified, "/a.jpg", NOW);

        String key = service.fetchFor(post.getId()).orElseThrow();

        assertThat(reload(post).getThumbnailKey()).isEqualTo(key);
        Path file = root.resolve("thumb/external").resolve(key.substring(0, 2)).resolve(key + ".jpg");
        assertThat(file).exists();
        BufferedImage out = TestImages.read(readAll(file));
        assertThat(out.getWidth()).isEqualTo(600);
        assertThat(out.getHeight()).isEqualTo(400);
        assertThat(service.file(key)).hasValueSatisfying(s -> assertThat(s.mime()).isEqualTo("image/jpeg"));
        // 이미 있으면 다시 받지 않는다
        server.clearRequests();
        assertThat(service.fetchFor(post.getId())).isEmpty();
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void pngGifAndWebpBecomePng() {
        image("/b.png", "image/png", TestImages.png(300, 300));
        image("/c.gif", "image/gif", TestImages.ANIMATED_GIF_20x10);
        image("/d.webp", "image/webp", TestImages.WEBP_40x30);
        for (String path : List.of("/b.png", "/c.gif", "/d.webp")) {
            ExternalPost post = post(verified, path, NOW);
            String key = service.fetchFor(post.getId()).orElseThrow();
            assertThat(service.file(key)).hasValueSatisfying(s -> {
                assertThat(s.mime()).isEqualTo("image/png");
                assertThat(s.path().getFileName().toString()).endsWith(".png");
                BufferedImage out = TestImages.read(readAll(s.path()));
                // 원본보다 키우지 않는 3:2 상자
                assertThat(out.getWidth() * 2).isCloseTo(out.getHeight() * 3, org.assertj.core.data.Offset.offset(3));
                assertThat(out.getWidth()).isLessThanOrEqualTo(600);
            });
        }
    }

    @Test
    void unverifiedBlogMakesNoRequest() {
        image("/a.jpg", "image/jpeg", TestImages.jpeg(800, 600));
        ExternalPost post = post(unverified, "/a.jpg", NOW);

        assertThat(service.fetchFor(post.getId())).isEmpty();

        assertThat(server.requests()).isEmpty();
        assertThat(reload(post).getThumbnailKey()).isNull();
    }

    @Test
    void removedPostOrMissingImageIsSkipped() {
        ExternalPost noImage = post(verified, null, NOW);
        ExternalPost removed = post(verified, "/a.jpg", NOW);
        removed.remove(RemovedReason.ADMIN);
        em.flush();

        assertThat(service.fetchFor(noImage.getId())).isEmpty();
        assertThat(service.fetchFor(removed.getId())).isEmpty();
        assertThat(service.fetchFor(999_999L)).isEmpty();
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void notAnImageOrTooLargeGivesNoThumbnail() throws Exception {
        image("/x.svg", "image/svg+xml", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes());
        image("/x.html", "text/html", "<html></html>".getBytes());
        image("/big.png", "image/png", new byte[6 * 1024 * 1024]);
        server.respondBytes("/err.png", 500, "text/plain", "x".getBytes(), Map.of());
        for (String path : List.of("/x.svg", "/x.html", "/big.png", "/err.png")) {
            ExternalPost post = post(verified, path, NOW);
            assertThat(service.fetchFor(post.getId())).as(path).isEmpty();
            assertThat(reload(post).getThumbnailKey()).isNull();
        }
        Path dir = root.resolve("thumb/external");
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                assertThat(files.filter(Files::isRegularFile)).isEmpty();
            }
        }
    }

    @Test
    void pixelLimitGivesNoThumbnail() {
        ExternalThumbnailService small = service(TestImages.properties(root, DataSize.ofMegabytes(10),
                DataSize.ofMegabytes(200), 10_000L));
        image("/huge.png", "image/png", TestImages.png(200, 200));
        ExternalPost post = post(verified, "/huge.png", NOW);

        assertThat(small.fetchFor(post.getId())).isEmpty();
    }

    @Test
    void backfillTakesRecentPostsWithImagesOnly() {
        image("/a.png", "image/png", TestImages.png(60, 40));
        ExternalPost recent = post(verified, "/a.png", NOW.minus(Duration.ofDays(3)));
        ExternalPost old = post(verified, "/a.png", NOW.minus(Duration.ofDays(31)));
        ExternalPost noImage = post(verified, null, NOW);
        ExternalPost other = post(unverified, "/a.png", NOW);

        assertThat(service.backfill(verified.getId())).isEqualTo(1);

        assertThat(reload(recent).getThumbnailKey()).isNotNull();
        assertThat(reload(old).getThumbnailKey()).isNull();
        assertThat(reload(noImage).getThumbnailKey()).isNull();
        assertThat(reload(other).getThumbnailKey()).isNull();
        assertThat(service.backfill(verified.getId())).isZero();
    }

    @Test
    void deleteRemovesFilesAndIgnoresBadKeys() {
        image("/a.png", "image/png", TestImages.png(60, 40));
        ExternalPost post = post(verified, "/a.png", NOW);
        String key = service.fetchFor(post.getId()).orElseThrow();
        assertThat(service.file(key)).isPresent();

        service.delete(List.of(key, "../etc/passwd", "short"));

        assertThat(service.file(key)).isEmpty();
        assertThat(service.file("../etc/passwd")).isEmpty();
        assertThat(service.root()).isEqualTo(root.resolve("thumb").toAbsolutePath().normalize().resolve("external"));
    }

    private static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
