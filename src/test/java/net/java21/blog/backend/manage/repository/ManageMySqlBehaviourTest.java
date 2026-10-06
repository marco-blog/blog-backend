package net.java21.blog.backend.manage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.audit.AdminAuditLog;
import net.java21.blog.backend.admin.audit.AdminAuditLogRepository;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.manage.dto.ManagePostFilter;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 스키마(MySQL)에서 확인하는 블로그 관리·작업 기록 동작:
 * <ul>
 *   <li>일괄 휴지통 UPDATE가 MySQL의 SET 왼쪽부터 평가 규칙에서도 직전 상태({@code status_before_delete})를 바르게 남긴다.</li>
 *   <li>관리 목록의 작성 중 사본 LEFT JOIN과 제목 검색(대소문자 무시)이 MySQL 콜레이션에서 동작한다.</li>
 *   <li>작업 기록의 JSON 전후 값({@code null} 포함)과 암호화된 요청 IP가 실제 컬럼(JSON, VARBINARY(128))에 저장된다.</li>
 * </ul>
 */
@MySqlRepositoryTest
@Import(ManagePostQueryRepository.class)
class ManageMySqlBehaviourTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private ManagePostQueryRepository repository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bulkTrashKeepsPreviousStatusOnMySql() {
        Blog blog = persistBlog();
        Post published = persistPost(blog, "Spring Boot");
        published.publish("Spring Boot", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW);
        Post draft = persistPost(blog, "임시");
        PostDraft copy = new PostDraft(draft);
        copy.write("임시", "본문", null, List.of(), NOW);
        em.persist(copy);
        em.flush();

        assertThat(repository.findPosts(blog.getId(), new ManagePostFilter(null, null, null, "spring"),
                NOW.minusSeconds(86_400), PageRequest.of(0, 20)).getContent())
                .extracting(ManagePostRow::id).containsExactly(published.getId());
        assertThat(repository.findPosts(blog.getId(), ManagePostFilter.ALL, NOW, PageRequest.of(0, 20))
                .getContent()).extracting(ManagePostRow::hasDraft).containsExactly(true, false);

        assertThat(repository.moveToTrash(blog.getId(), List.of(published.getId(), draft.getId()), NOW))
                .isEqualTo(2);

        assertThat(jdbc.queryForList("SELECT status, status_before_delete FROM posts WHERE blog_id = ? ORDER BY id",
                blog.getId())).containsExactly(
                Map.of("status", "DELETED", "status_before_delete", "PUBLISHED"),
                Map.of("status", "DELETED", "status_before_delete", "DRAFT"));
    }

    @Test
    void auditLogFitsTheRealColumns() {
        User admin = persistUser();
        java.util.HashMap<String, Object> before = new java.util.HashMap<>();
        before.put("maxBlogs", null);
        auditLogRepository.save(new AdminAuditLog(admin, "USER_BLOG_LIMIT_CHANGE", "USER", admin.getId(), null,
                before, Map.of("maxBlogs", 0), null, "2001:db8:85a3:0000:0000:8a2e:0370:7334"));
        em.flush();
        em.clear();

        assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(after_json, '$.maxBlogs') FROM admin_audit_logs "
                + "WHERE admin_id = ?", String.class, admin.getId())).isEqualTo("0");
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("USER", admin.getId()))
                .singleElement().satisfies(log -> {
                    assertThat(log.getBefore()).containsEntry("maxBlogs", null);
                    assertThat(log.getRequestIp()).isEqualTo("2001:db8:85a3:0000:0000:8a2e:0370:7334");
                });
    }

    private User persistUser() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        User u = new User(unique + "@example.com", (unique + unique).substring(0, 64), "$2a$hash", "관리자", null,
                null, "2026-10-06", NOW);
        em.persist(u);
        return u;
    }

    private Blog persistBlog() {
        Blog b = new Blog(persistUser(), "m" + UUID.randomUUID().toString().substring(0, 8), "블로그");
        em.persist(b);
        return b;
    }

    private Post persistPost(Blog blog, String title) {
        Post p = new Post(blog, title);
        em.persist(p);
        return p;
    }
}
