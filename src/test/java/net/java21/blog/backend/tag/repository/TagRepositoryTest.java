package net.java21.blog.backend.tag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.tag.domain.Tag;
import net.java21.blog.backend.tag.dto.BlogTagResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 태그 조회(T167, FR-025·026): 블로그 태그 목록 {@code [{ name, postCount }]}, 서비스 전체 태그별 글(목록 노출 가능만, 최신순,
 * blogHandle 포함), 목록의 {@code tags}를 글 수와 무관한 쿼리 1회로 채움(N+1 없음), 글 태그 교체.
 */
@JpaRepositoryTest
@Import(TagQueryRepository.class)
class TagRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private TagQueryRepository repository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private PostTagRepository postTagRepository;

    private JpaFixtures fx;
    private Blog marco;
    private Blog other;
    private Tag spring;
    private Tag jpa;
    private Tag life;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        marco = fx.blog(fx.user("marco"), "marco");
        other = fx.blog(fx.user("other"), "other");
        spring = fx.tag("spring");
        jpa = fx.tag("jpa");
        life = fx.tag("일상");
    }

    @Test
    void blogTagsCountListablePostsOnlyMostUsedFirst() {
        fx.tagPost(fx.published(marco, "A", null, 1), spring, jpa);
        fx.tagPost(fx.published(marco, "B", null, 2), spring);
        fx.tagPost(fx.post(marco, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 3), jpa, life);
        fx.tagPost(fx.post(marco, "임시", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 4), life);
        fx.tagPost(fx.post(marco, "휴지통", null, PostStatus.DELETED, PostVisibility.PUBLIC, 5), life);
        fx.tagPost(fx.published(other, "남의 글", null, 1), life, spring);
        fx.flushAndClear();

        queryCounter.reset();
        List<BlogTagResponse> tags = repository.findBlogTags(marco.getId());

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(tags).containsExactly(new BlogTagResponse("spring", 2), new BlogTagResponse("jpa", 1));
    }

    @Test
    void taggedPostsAcrossServiceListableOnlyNewestFirstWithHandle() {
        Post a = fx.published(marco, "A", null, 1);
        Post b = fx.published(other, "B", null, 3);
        Post c = fx.published(marco, "C", null, 2);
        fx.tagPost(a, spring);
        fx.tagPost(b, spring, jpa);
        fx.tagPost(c, spring);
        fx.tagPost(fx.post(marco, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 9), spring);
        fx.tagPost(fx.published(marco, "다른 태그", null, 8), jpa);
        Blog deleted = fx.blog(marco.getUser(), "gone");
        fx.tagPost(fx.published(deleted, "삭제된 블로그 글", null, 10), spring);
        ReflectionTestUtils.setField(deleted, "status", BlogStatus.DELETED);
        fx.flushAndClear();

        queryCounter.reset();
        Page<TaggedPostRow> page = repository.findTaggedPosts("spring", PageRequest.of(0, 2));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(TaggedPostRow::id).containsExactly(b.getId(), c.getId());
        assertThat(page.getContent()).extracting(TaggedPostRow::blogHandle).containsExactly("other", "marco");
        assertThat(repository.findTaggedPosts("없는 태그", PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void tagNamesForManyPostsInOneQuery() {
        Category cat = fx.category(marco, null, "Spring", 0);
        List<Long> ids = new ArrayList<>();
        List<Tag> pool = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            pool.add(fx.tag("t" + i));
        }
        for (int i = 0; i < 25; i++) {
            Post p = fx.published(marco, "글 " + i, cat, i);
            fx.tagPost(p, pool.get(i % 12), pool.get((i + 1) % 12), spring);
            ids.add(p.getId());
        }
        Post untagged = fx.published(marco, "태그 없음", null, 99);
        ids.add(untagged.getId());
        fx.flushAndClear();

        queryCounter.reset();
        Map<Long, List<String>> names = repository.findTagNames(ids);

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(names).hasSize(25);
        assertThat(names.get(ids.getFirst())).containsExactly("spring", "t0", "t1");
        assertThat(names.get(untagged.getId())).isNull();
        queryCounter.reset();
        assertThat(repository.findTagNames(List.of())).isEmpty();
        assertThat(queryCounter.count()).isZero();
    }

    @Test
    void tagLookupAndPostTagReplacement() {
        Post p = fx.published(marco, "A", null, 1);
        fx.tagPost(p, spring, jpa);
        Post q = fx.published(marco, "B", null, 2);
        fx.tagPost(q, spring);
        fx.flushAndClear();

        assertThat(tagRepository.findByNameIn(List.of("spring", "없음"))).extracting(Tag::getName)
                .containsExactly("spring");
        assertThat(tagRepository.findByName("jpa")).isPresent();

        queryCounter.reset();
        assertThat(postTagRepository.deleteByPostId(p.getId())).isEqualTo(2);
        assertThat(queryCounter.count()).isEqualTo(1);
        em.clear();
        assertThat(repository.findTagNames(List.of(p.getId(), q.getId())))
                .isEqualTo(Map.of(q.getId(), List.of("spring")));
    }
}
