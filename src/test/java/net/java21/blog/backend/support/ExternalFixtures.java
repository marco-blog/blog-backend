package net.java21.blog.backend.support;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;

/** 007 외부 블로그·글 픽스처(H2·MySQL 저장소 시험). 상태는 엔티티의 전이 메서드로 만든다. */
public class ExternalFixtures {

    public static final Instant T0 = JpaFixtures.T0;

    private final EntityManager em;
    private int seq;

    public ExternalFixtures(EntityManager em) {
        this.em = em;
    }

    /** 피드 주소 {@code feedUrl}의 등록. {@code member}가 null이면 운영자 직접 등록(ACTIVE에서 시작). */
    public ExternalBlog blog(User member, String feedUrl, Topic topic, ExternalBlogStatus status) {
        String hash = FeedUrlNormalizer.hash(feedUrl);
        ExternalBlog blog = member == null
                ? ExternalBlog.adminDirect(null, "basis", feedUrl, hash, topic, T0)
                : ExternalBlog.memberRequest(member, feedUrl, hash, topic);
        blog.describe("Blog " + feedUrl, feedUrl.replaceAll("/[^/]*$", "/"), null);
        moveTo(blog, status);
        em.persist(blog);
        return blog;
    }

    public ExternalBlog blog(User member, Topic topic, ExternalBlogStatus status) {
        return blog(member, "https://ext" + (++seq) + ".example/feed", topic, status);
    }

    /** 이미 저장된 등록을 {@code status}로. */
    public static void moveTo(ExternalBlog blog, ExternalBlogStatus status) {
        if (blog.getStatus() == status) {
            return;
        }
        if (blog.getStatus() == ExternalBlogStatus.PENDING && status != ExternalBlogStatus.REJECTED
                && status != ExternalBlogStatus.RELEASED && status != ExternalBlogStatus.BLOCKED) {
            blog.approve(null, T0);
        }
        switch (status) {
            case PENDING, ACTIVE -> {
            }
            case REJECTED -> blog.reject(null, "no", T0);
            case PAUSED -> blog.pause();
            case STOPPED -> blog.stop();
            case BLOCKED -> blog.block();
            case RELEASED -> blog.release();
        }
    }

    /** 공개 외부 글. {@code publishedAt}이 null이면 T0. */
    public ExternalPost post(ExternalBlog blog, String title, Topic topic, Instant publishedAt) {
        int n = ++seq;
        String link = "https://post.example/" + blog.getId() + "/" + n;
        FeedItem item = new FeedItem("guid-" + n, link, title, "summary " + title, null,
                publishedAt == null ? T0 : publishedAt, List.of("tag" + n));
        ExternalPost post = new ExternalPost(blog, item, FeedUrlNormalizer.sha256("guid-" + n),
                FeedUrlNormalizer.hash(link), topic, TopicSource.DEFAULT, T0);
        em.persist(post);
        return post;
    }

    public ExternalPost removed(ExternalBlog blog, String title, Topic topic, RemovedReason reason) {
        ExternalPost post = post(blog, title, topic, T0);
        post.remove(reason);
        return post;
    }

    public ClassificationReview review(ExternalPost post, Topic predicted, double confidence) {
        ClassificationReview review = new ClassificationReview(post, predicted, confidence);
        em.persist(review);
        return review;
    }
}
