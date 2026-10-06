package net.java21.blog.backend.media.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.media.domain.PostMedia;
import net.java21.blog.backend.media.domain.PostMediaSource;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 이미지 조회·정리 쿼리(T202): 회원별 TEMP 합계, 정리 후보(만료 TEMP·ORPHANED), 참조 존재 확인(post_media·프로필·블로그 대표 이미지)을
 * 이미지 수와 관계없는 쿼리 수로.
 */
@JpaRepositoryTest
@Import(MediaQueryRepository.class)
class MediaRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:00:00Z");

    @Autowired
    private MediaQueryRepository repository;
    @Autowired
    private MediaRepository mediaRepository;
    @Autowired
    private PostMediaRepository postMediaRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private QueryCounter queryCounter;

    private User marco;
    private User other;
    private Blog blog;
    private Post post;
    private int seq;

    @BeforeEach
    void setUp() {
        marco = persistUser("marco", "a");
        other = persistUser("other", "b");
        blog = new Blog(marco, "marco", "블로그");
        em.persist(blog);
        post = new Post(blog, "글");
        em.persist(post);
    }

    @Test
    void sumsOnlyTheMembersTempBytes() {
        media(marco, MediaStatus.TEMP, 100);
        media(marco, MediaStatus.TEMP, 50);
        media(marco, MediaStatus.ATTACHED, 1000);
        media(other, MediaStatus.TEMP, 7);
        flushAndClear();

        assertThat(repository.sumTempBytes(marco.getId())).isEqualTo(150);
        assertThat(repository.sumTempBytes(other.getId())).isEqualTo(7);
        assertThat(repository.sumTempBytes(-1L)).isZero();
    }

    @Test
    void findsFileRowAndOwnedImagesByKey() {
        Media mine = media(marco, MediaStatus.TEMP, 10);
        Media theirs = media(other, MediaStatus.ATTACHED, 10);
        flushAndClear();

        MediaFileRow row = repository.findFile(mine.getMediaKey()).orElseThrow();
        assertThat(row.ownerId()).isEqualTo(marco.getId());
        assertThat(row.isTemp()).isTrue();
        assertThat(row.mime()).isEqualTo("image/png");
        assertThat(repository.findFile("ZZZZZZZZZZZZZZZZZZZZZZ")).isEmpty();

        assertThat(repository.findOwnedByKeys(marco.getId(), List.of(mine.getMediaKey(), theirs.getMediaKey())))
                .extracting(Media::getId).containsExactly(mine.getId());
        assertThat(repository.findOwnedByKeys(marco.getId(), List.of())).isEmpty();
        assertThat(mediaRepository.findByMediaKey(theirs.getMediaKey())).isPresent();
    }

    @Test
    void cleanupCandidatesAreExpiredTempAndOrphaned() {
        Media expired = media(marco, MediaStatus.TEMP, 10);
        Media fresh = media(marco, MediaStatus.TEMP, 10);
        Media orphan = media(marco, MediaStatus.ORPHANED, 10);
        media(marco, MediaStatus.ATTACHED, 10);
        em.flush();
        createdAt(expired, NOW.minusSeconds(3600 * 25));
        createdAt(fresh, NOW.minusSeconds(3600));
        em.clear();

        Instant cutoff = NOW.minusSeconds(3600 * 24);
        queryCounter.reset();
        List<CleanupCandidate> candidates = repository.findCleanupCandidates(cutoff, 10);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(candidates).extracting(CleanupCandidate::id).containsExactly(expired.getId(), orphan.getId());
        assertThat(candidates.get(0).storedPath()).isEqualTo(expired.getStoredPath());
        assertThat(repository.findCleanupCandidates(cutoff, 1)).hasSize(1);

        // 고른 뒤 다시 참조돼 상태가 바뀌었으면 지우지 않는다.
        jdbc.update("UPDATE media SET status = 'ATTACHED' WHERE id = ?", orphan.getId());
        assertThat(repository.deleteIfStill(orphan.getId(), MediaStatus.ORPHANED)).isZero();
        assertThat(repository.deleteIfStill(expired.getId(), MediaStatus.TEMP)).isEqualTo(1);
        assertThat(mediaRepository.findById(expired.getId())).isEmpty();
    }

    @Test
    void orphanedOnlyWhenNoPostProfileOrCoverReferencesAndInOneQuery() {
        Media unused = media(marco, MediaStatus.ATTACHED, 10);
        Media inDraftOnly = media(marco, MediaStatus.ATTACHED, 10);
        Media inPublished = media(marco, MediaStatus.ATTACHED, 10);
        Media profile = media(marco, MediaStatus.ATTACHED, 10);
        Media cover = media(marco, MediaStatus.ATTACHED, 10);
        Media temp = media(marco, MediaStatus.TEMP, 10);
        em.persist(new PostMedia(post.getId(), inDraftOnly.getId(), PostMediaSource.DRAFT, NOW));
        em.persist(new PostMedia(post.getId(), inPublished.getId(), PostMediaSource.PUBLISHED, NOW));
        marco.changeProfileMedia(profile);
        blog.changeCoverMedia(cover);
        flushAndClear();

        List<Long> ids = List.of(unused.getId(), inDraftOnly.getId(), inPublished.getId(), profile.getId(),
                cover.getId(), temp.getId());
        queryCounter.reset();
        long orphaned = repository.markOrphanedIfUnreferenced(ids);
        assertThat(queryCounter.count()).isEqualTo(1);

        assertThat(orphaned).isEqualTo(1);
        assertThat(status(unused)).isEqualTo("ORPHANED");
        assertThat(status(inDraftOnly)).isEqualTo("ATTACHED");
        assertThat(status(inPublished)).isEqualTo("ATTACHED");
        assertThat(status(profile)).isEqualTo("ATTACHED");
        assertThat(status(cover)).isEqualTo("ATTACHED");
        assertThat(status(temp)).isEqualTo("TEMP");
        assertThat(repository.markOrphanedIfUnreferenced(List.of())).isZero();
    }

    @Test
    void referenceCheckQueryCountDoesNotDependOnImageCount() {
        List<Long> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Media media = media(marco, MediaStatus.ATTACHED, 10);
            many.add(media.getId());
            if (i % 2 == 0) {
                em.persist(new PostMedia(post.getId(), media.getId(), PostMediaSource.PUBLISHED, NOW));
            }
        }
        flushAndClear();

        queryCounter.reset();
        assertThat(repository.markOrphanedIfUnreferenced(many)).isEqualTo(15);
        assertThat(queryCounter.count()).isEqualTo(1);
    }

    @Test
    void postMediaRowsBySourceAndByPosts() {
        Media a = media(marco, MediaStatus.ATTACHED, 10);
        Media b = media(marco, MediaStatus.ATTACHED, 10);
        Post second = new Post(blog, "둘째");
        em.persist(second);
        postMediaRepository.saveAll(List.of(
                new PostMedia(post.getId(), a.getId(), PostMediaSource.PUBLISHED, NOW),
                new PostMedia(post.getId(), a.getId(), PostMediaSource.DRAFT, NOW),
                new PostMedia(post.getId(), b.getId(), PostMediaSource.DRAFT, NOW),
                new PostMedia(second.getId(), b.getId(), PostMediaSource.PUBLISHED, NOW)));
        flushAndClear();

        assertThat(postMediaRepository.findMediaIds(post.getId(), PostMediaSource.DRAFT))
                .containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(postMediaRepository.deleteByPostAndSource(post.getId(), PostMediaSource.DRAFT)).isEqualTo(2);
        assertThat(postMediaRepository.findMediaIds(post.getId(), PostMediaSource.PUBLISHED)).containsExactly(a.getId());
        assertThat(postMediaRepository.findMediaIdsOfPosts(List.of(post.getId(), second.getId())))
                .containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(postMediaRepository.deleteByPosts(List.of(post.getId(), second.getId()))).isEqualTo(2);
        assertThat(postMediaRepository.count()).isZero();
        PostMedia loaded = new PostMedia(post.getId(), a.getId(), PostMediaSource.DRAFT, NOW);
        postMediaRepository.saveAndFlush(loaded);
        em.clear();
        assertThat(postMediaRepository.findById(loaded.getId()).orElseThrow().isNew()).isFalse();
    }

    @Test
    void coverImageIdsOfBlogs() {
        Media cover = media(marco, MediaStatus.ATTACHED, 10);
        blog.changeCoverMedia(cover);
        Blog bare = new Blog(other, "other", "다른");
        em.persist(bare);
        flushAndClear();

        assertThat(repository.findCoverMediaIds(List.of(blog.getId(), bare.getId()))).containsExactly(cover.getId());
        assertThat(repository.findCoverMediaIds(List.of())).isEmpty();
    }

    private String status(Media media) {
        return jdbc.queryForObject("SELECT status FROM media WHERE id = ?", String.class, media.getId());
    }

    private void createdAt(Media media, Instant at) {
        jdbc.update("UPDATE media SET created_at = ? WHERE id = ?", Timestamp.from(at), media.getId());
    }

    private Media media(User owner, MediaStatus status, int size) {
        String key = ("k" + (++seq) + "xxxxxxxxxxxxxxxxxxxxxxxx").substring(0, 22);
        Media media = new Media(owner, key, MediaPurpose.POST, key + ".png", status == MediaStatus.TEMP
                ? key + ".png" : "2026/10/" + key + ".png", "image/png", size, 2, 2);
        ReflectionTestUtils.setField(media, "status", status);
        em.persist(media);
        return media;
    }

    private User persistUser(String nickname, String hashChar) {
        User user = new User(nickname + "@example.com", hashChar.repeat(64), "$2a$hash", nickname, null, null,
                "2026-10-06", NOW);
        em.persist(user);
        return user;
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
