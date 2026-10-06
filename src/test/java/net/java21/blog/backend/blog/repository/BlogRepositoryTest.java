package net.java21.blog.backend.blog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.auth.PasswordConfig;
import net.java21.blog.backend.blog.BlogsProperties;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.dto.BlogLink;
import net.java21.blog.backend.blog.dto.MyBlogsResponse;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.blog.service.BlogService;
import net.java21.blog.backend.category.repository.CategoryQueryRepository;
import net.java21.blog.backend.blog.service.HandlePolicy;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/** 블로그 조회·집계(T056): ACTIVE handle 조회, 회원별 ACTIVE 수, 회원 행 잠금, {@code /me/blogs} 고정 쿼리 수. */
@JpaRepositoryTest
@Import({BlogRepositoryTest.Services.class})
class BlogRepositoryTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BlogsProperties.class)
    @Import({BlogQueryRepository.class, CategoryQueryRepository.class, BlogService.class, BlogAccess.class, HandlePolicy.class, PasswordConfig.class})
    static class Services {
    }

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private BlogRepository blogRepository;
    @Autowired
    private BlogQueryRepository blogQueryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BlogService blogService;
    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;

    private User marco;
    private User other;

    @BeforeEach
    void setUp() {
        marco = persistUser("marco", "a");
        other = persistUser("other", "b");
    }

    @Test
    void findByHandleWithOwnerFetchesOwnerAndIncludesDeletedBlogs() {
        Blog deleted = persistBlog(marco, "old-blog");
        deleted.delete(NOW);
        persistBlog(marco, "marco");
        flushAndClear();

        queryCounter.reset();
        Blog blog = blogRepository.findByHandleWithOwner("marco").orElseThrow();
        assertThat(Hibernate.isInitialized(blog.getUser())).isTrue();
        assertThat(blog.getUser().getNickname()).isEqualTo("marco");
        assertThat(queryCounter.count()).isEqualTo(1);

        assertThat(blogRepository.findByHandleWithOwner("old-blog").orElseThrow().getStatus())
                .isEqualTo(BlogStatus.DELETED);
        assertThat(blogRepository.findByHandleWithOwner("none")).isEmpty();
        assertThat(blogRepository.existsByHandle("old-blog")).isTrue();
        assertThat(blogRepository.existsByHandle("none")).isFalse();
    }

    @Test
    void countsOnlyActiveBlogsOfTheMember() {
        persistBlog(marco, "marco");
        persistBlog(marco, "marco-dev");
        persistBlog(marco, "marco-old").delete(NOW);
        persistBlog(other, "other");
        flushAndClear();

        assertThat(blogRepository.countByUserIdAndStatus(marco.getId(), BlogStatus.ACTIVE)).isEqualTo(2);
        assertThat(blogRepository.countByUserIdAndStatus(other.getId(), BlogStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void findByIdForUpdateLocksTheMemberRow() {
        flushAndClear();
        queryCounter.reset();
        User locked = userRepository.findByIdForUpdate(marco.getId()).orElseThrow();
        assertThat(locked.getNickname()).isEqualTo("marco");
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(em.getLockMode(locked)).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void myBlogsProjectionCountsPublishedPostsPerBlogInCreationOrder() {
        Blog first = persistBlog(marco, "marco");
        Blog second = persistBlog(marco, "marco-dev");
        persistBlog(marco, "marco-old").delete(NOW);
        Blog others = persistBlog(other, "other");
        persistPost(first, PostStatus.PUBLISHED);
        persistPost(first, PostStatus.PUBLISHED);
        persistPost(first, PostStatus.DRAFT);
        persistPost(first, PostStatus.DELETED);
        persistPost(others, PostStatus.PUBLISHED);
        flushAndClear();

        List<MyBlogRow> rows = blogQueryRepository.findMyBlogs(marco.getId());

        assertThat(rows).extracting(MyBlogRow::handle).containsExactly("marco", "marco-dev");
        assertThat(rows).extracting(MyBlogRow::postCount).containsExactly(2L, 0L);
        assertThat(rows.get(0).createdAt()).isNotNull();
        assertThat(rows.get(1).id()).isEqualTo(second.getId());
    }

    @Test
    void myBlogsQueryCountDoesNotDependOnBlogCount() {
        Blog first = persistBlog(marco, "marco");
        persistPost(first, PostStatus.PUBLISHED);
        flushAndClear();
        queryCounter.reset();
        MyBlogsResponse one = blogService.myBlogs(marco.getId());
        long queriesForOne = queryCounter.count();

        for (int i = 1; i <= 4; i++) {
            Blog blog = persistBlog(marco, "marco-" + i);
            persistPost(blog, PostStatus.PUBLISHED);
            persistPost(blog, PostStatus.PUBLISHED);
        }
        flushAndClear();
        queryCounter.reset();
        MyBlogsResponse five = blogService.myBlogs(marco.getId());

        assertThat(one.count()).isEqualTo(1);
        assertThat(five.count()).isEqualTo(5);
        assertThat(five.limit()).isEqualTo(3);
        assertThat(five.items()).extracting(MyBlogsResponse.Item::postCount).containsExactly(1L, 2L, 2L, 2L, 2L);
        // 회원 1회 + 블로그 목록(글 수 포함) 1회
        assertThat(queriesForOne).isEqualTo(2);
        assertThat(queryCounter.count()).isEqualTo(2);
    }

    @Test
    void activeBlogLinksInCreationOrder() {
        persistBlog(marco, "marco");
        persistBlog(marco, "marco-old").delete(NOW);
        persistBlog(marco, "marco-dev");
        flushAndClear();

        queryCounter.reset();
        List<BlogLink> links = blogQueryRepository.findActiveBlogLinks(marco.getId());
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(links).containsExactly(new BlogLink("marco", "marco의 블로그"),
                new BlogLink("marco-dev", "marco의 블로그"));
    }

    @Test
    void moveAllPostsToTrashKeepsPreviousStatusInOneUpdate() {
        Blog blog = persistBlog(marco, "marco");
        Blog keep = persistBlog(marco, "marco-dev");
        Post published = persistPost(blog, PostStatus.PUBLISHED);
        Post draft = persistPost(blog, PostStatus.DRAFT);
        Post trashed = persistPost(blog, PostStatus.DELETED);
        ReflectionTestUtils.setField(trashed, "statusBeforeDelete", PostStatus.PUBLISHED);
        Post untouched = persistPost(keep, PostStatus.PUBLISHED);
        flushAndClear();

        queryCounter.reset();
        long moved = blogQueryRepository.moveAllPostsToTrash(blog.getId(), NOW);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(moved).isEqualTo(2);
        em.clear();

        Post p = em.find(Post.class, published.getId());
        assertThat(p.getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(p.getStatusBeforeDelete()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(p.getDeletedAt()).isEqualTo(NOW);
        Post d = em.find(Post.class, draft.getId());
        assertThat(d.getStatusBeforeDelete()).isEqualTo(PostStatus.DRAFT);
        assertThat(em.find(Post.class, trashed.getId()).getStatusBeforeDelete()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(em.find(Post.class, untouched.getId()).getStatus()).isEqualTo(PostStatus.PUBLISHED);
    }

    private User persistUser(String nickname, String hashChar) {
        User user = new User(nickname + "@example.com", hashChar.repeat(64), "$2a$hash", nickname, null, null,
                "2026-10-06", NOW);
        em.persist(user);
        return user;
    }

    private Blog persistBlog(User user, String handle) {
        Blog blog = new Blog(user, handle, Blog.defaultTitle(user.getNickname()));
        em.persist(blog);
        return blog;
    }

    private Post persistPost(Blog blog, PostStatus status) {
        Post post = new Post(blog, "글");
        ReflectionTestUtils.setField(post, "status", status);
        em.persist(post);
        return post;
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
