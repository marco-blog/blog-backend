package net.java21.blog.backend.support;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.tag.domain.PostTag;
import net.java21.blog.backend.tag.domain.Tag;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.domain.TopicNames;
import net.java21.blog.backend.user.domain.User;

/** {@code @JpaRepositoryTest}에서 회원·블로그·글·카테고리·태그를 저장하는 도우미(US2 리포지토리 테스트 공용). */
public class JpaFixtures {

    public static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private final EntityManager em;
    private int hashSeq;

    public JpaFixtures(EntityManager em) {
        this.em = em;
    }

    public User user(String nickname) {
        String hash = String.valueOf((char) ('a' + hashSeq++)).repeat(64);
        User u = new User(nickname + "@example.com", hash, "$2a$hash", nickname, null, null, "2026-10-06", T0);
        em.persist(u);
        return u;
    }

    public Blog blog(User owner, String handle) {
        Blog b = new Blog(owner, handle, owner.getNickname() + " 블로그");
        em.persist(b);
        return b;
    }

    public Category category(Blog blog, Category parent, String name, int sortOrder) {
        Category c = new Category(blog, parent, name, sortOrder);
        em.persist(c);
        return c;
    }

    /** 발행한 공개 글. {@code publishedAt}은 T0 + {@code minutes}분. */
    public Post published(Blog blog, String title, Category category, int minutes) {
        return post(blog, title, category, PostStatus.PUBLISHED, PostVisibility.PUBLIC, minutes);
    }

    public Post post(Blog blog, String title, Category category, PostStatus status, PostVisibility visibility,
            int minutes) {
        Post p = new Post(blog, title);
        p.classify(category);
        if (status != PostStatus.DRAFT) {
            p.publish(title, "본문", "<p>본문</p>", "본문", "요약 " + title, null, visibility, true,
                    T0.plusSeconds(60L * minutes));
        }
        if (status == PostStatus.DELETED) {
            p.moveToTrash(T0.plusSeconds(60L * minutes + 1));
        }
        em.persist(p);
        return p;
    }

    public Tag tag(String name) {
        Tag t = new Tag(name);
        em.persist(t);
        return t;
    }

    public void tagPost(Post post, Tag... tags) {
        for (Tag tag : tags) {
            em.persist(new PostTag(post, tag));
        }
    }

    public PostDraft draft(Post post, Long categoryId, List<String> tags) {
        PostDraft d = new PostDraft(post);
        d.write(post.getTitle(), "본문", categoryId, tags, T0);
        em.persist(d);
        return d;
    }

    /** 주제(003). 이름은 slug로 4개 언어를 채운다. */
    public Topic topic(Topic parent, String slug, int sortOrder) {
        Topic t = new Topic(parent, slug, new TopicNames(slug + "-ko", slug + "-en", slug + "-ja", slug + "-zh"),
                sortOrder, parent == null ? "#3D7DD8" : null, false);
        em.persist(t);
        return t;
    }

    /**
     * 본문 텍스트를 정해 발행한 글(003 포털 길이 조건). {@code publishedAt}은 {@code publishedAt} 그대로, 주제는 {@code topic}.
     */
    public Post publishedText(Blog blog, String title, String text, Topic topic, PostStatus status,
            PostVisibility visibility, Instant publishedAt) {
        Post p = new Post(blog, title);
        p.assignTopic(topic);
        if (status != PostStatus.DRAFT) {
            p.publish(title, text, "<p>" + text + "</p>", text, "요약 " + title, null, visibility, true, publishedAt);
        }
        if (status == PostStatus.DELETED) {
            p.moveToTrash(publishedAt.plusSeconds(1));
        }
        em.persist(p);
        return p;
    }

    /** 공개 발행 글(본문 텍스트 지정). */
    public Post publishedText(Blog blog, String title, String text, Topic topic, Instant publishedAt) {
        return publishedText(blog, title, text, topic, PostStatus.PUBLISHED, PostVisibility.PUBLIC, publishedAt);
    }

    /** 가입 시각을 바꾼다(JPA Auditing이 저장 때 채운 값을 덮어쓴다). */
    public void joinedAt(User user, Instant createdAt) {
        em.flush();
        em.createNativeQuery("UPDATE users SET created_at = :t WHERE id = :id")
                .setParameter("t", createdAt).setParameter("id", user.getId()).executeUpdate();
    }

    public void flushAndClear() {
        em.flush();
        em.clear();
    }
}
