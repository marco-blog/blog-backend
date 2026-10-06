package net.java21.blog.backend.support;

import java.time.Instant;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.crypto.CryptoProperties;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.domain.TopicNames;
import net.java21.blog.backend.user.domain.User;
import org.springframework.test.util.ReflectionTestUtils;

/** 서비스 단위 테스트용 엔티티 준비(저장하지 않고 ID·상태를 직접 넣는다). */
public final class TestEntities {

    /** 테스트 전용 고정 해시 키(비밀 아님, application-test.yml과 같은 값). */
    public static final PersonalDataHasher HASHER = new PersonalDataHasher(new CryptoProperties(1,
            java.util.Map.of(1, "dGVzdC1vbmx5LWtleS12MS0wMDAwMDAwMDAwMDAwMDA="),
            "dGVzdC1vbmx5LWhhc2gta2V5LTAwMDAwMDAwMDAwMDA="));

    private TestEntities() {
    }

    public static User user(long id, String email, String passwordHash, String nickname) {
        User user = new User(PersonalDataHasher.normalizeEmail(email), HASHER.hashEmail(email), passwordHash, nickname,
                "ko", null, "2026-10-06", Instant.parse("2026-10-06T00:00:00Z"));
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    public static User user(long id) {
        return user(id, "user" + id + "@example.com", "{hash}", "닉네임" + id);
    }

    public static Blog blog(long id, User owner, String handle) {
        Blog blog = new Blog(owner, handle, Blog.defaultTitle(owner.getNickname()));
        ReflectionTestUtils.setField(blog, "id", id);
        return blog;
    }

    /** 발행 전 글(DRAFT). */
    public static Post post(long id, Blog blog, String title) {
        Post post = new Post(blog, title);
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    /** 주제(대분류면 {@code parent} null). 이름은 slug로 4개 언어를 채운다. */
    public static Topic topic(long id, Topic parent, String slug) {
        Topic topic = new Topic(parent, slug, new TopicNames(slug + "-ko", slug + "-en", slug + "-ja", slug + "-zh"),
                0, null, false);
        ReflectionTestUtils.setField(topic, "id", id);
        return topic;
    }

    public static <T> T with(T entity, String field, Object value) {
        ReflectionTestUtils.setField(entity, field, value);
        return entity;
    }
}
