package net.java21.blog.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.dto.CreateCommentRequest;
import net.java21.blog.backend.comment.service.CommentService;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.subscription.service.SubscriptionService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림은 커밋 뒤에만 만든다(T043, 002 research D3). 전체 컨텍스트를 H2(MySQL 모드)로 띄운다: 댓글이 커밋되면 1건, 댓글 트랜잭션이
 * 롤백되면 0건, 구독하면 1건·이미 구독 중이면 더 만들지 않음, 알림 저장이 실패해도 댓글 API는 201.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthCookies.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notificationflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class NotificationAfterCommitIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @Autowired
    private CommentService commentService;
    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private long ownerId;
    private long readerId;
    private long postId;

    @BeforeEach
    void setUp() {
        for (String table : new String[] {"notifications", "comments", "blog_subscriptions", "posts", "blogs",
                "users"}) {
            jdbc.update("DELETE FROM " + table);
        }
        transactionTemplate.executeWithoutResult(status -> {
            JpaFixtures fx = new JpaFixtures(em);
            User owner = fx.user("owner");
            User reader = fx.user("reader");
            Blog blog = fx.blog(owner, "notiflow");
            Post post = fx.published(blog, "첫 글", null, 1);
            em.flush();
            ownerId = owner.getId();
            readerId = reader.getId();
            postId = post.getId();
        });
    }

    private long notifications(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = ? AND user_id = ?", Long.class,
                type, ownerId);
    }

    @Test
    void committedCommentCreatesOneNotificationOwnCommentNone() {
        commentService.create(readerId, postId, new CreateCommentRequest("좋은 글", null));
        commentService.create(ownerId, postId, new CreateCommentRequest("주인 댓글", null));

        assertThat(notifications("NEW_COMMENT")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM notifications", Long.class)).isEqualTo(readerId);
    }

    @Test
    void rolledBackCommentCreatesNothing() {
        transactionTemplate.executeWithoutResult(status -> {
            commentService.create(readerId, postId, new CreateCommentRequest("되돌릴 댓글", null));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments", Long.class)).isZero();
        assertThat(notifications("NEW_COMMENT")).isZero();
    }

    @Test
    void subscribingCreatesOneNotificationAlreadySubscribedNone() {
        subscriptionService.subscribe(readerId, "notiflow");
        subscriptionService.subscribe(readerId, "notiflow");

        assertThat(notifications("NEW_SUBSCRIBER")).isEqualTo(1);

        // 취소 후 24시간 안에 다시 구독해도 새 알림을 만들지 않는다(중복 방지)
        subscriptionService.unsubscribe(readerId, "notiflow");
        subscriptionService.subscribe(readerId, "notiflow");
        assertThat(notifications("NEW_SUBSCRIBER")).isEqualTo(1);
    }

    @Test
    void notificationFailureDoesNotFailCommentApi() throws Exception {
        jdbc.execute("ALTER TABLE notifications RENAME TO notifications_off");
        try {
            mvc.perform(post("/api/v1/posts/" + postId + "/comments").cookie(authCookies.user(readerId))
                            .header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"알림이 실패해도 댓글은 남는다\"}"))
                    .andExpect(status().isCreated());
        } finally {
            jdbc.execute("ALTER TABLE notifications_off RENAME TO notifications");
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments", Long.class)).isEqualTo(1);
        assertThat(notifications("NEW_COMMENT")).isZero();
    }
}
