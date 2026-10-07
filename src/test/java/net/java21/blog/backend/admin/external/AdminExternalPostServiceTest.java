package net.java21.blog.backend.admin.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.audit.AdminAuditLog;
import net.java21.blog.backend.admin.audit.AdminAuditLogRepository;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.dto.AdminExternalPostResponse;
import net.java21.blog.backend.external.dto.ExternalExclusionResponse;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/** 007 T073: 외부 글 내림과 포털 제외(US4 AS2, FR-127, FR-123). 작업 기록 대상은 {@code EXTERNAL_POST}. H2. */
@JpaRepositoryTest
class AdminExternalPostServiceTest {

    private static final String IP = "203.0.113.9";

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private PortalExclusionRepository exclusionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminAuditLogRepository auditRepository;

    private ApplicationEventPublisher events;
    private AdminExternalPostService service;
    private ExternalFixtures x;
    private User admin;
    private ExternalPost post;

    @BeforeEach
    void setUp() {
        JpaFixtures f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        admin = f.user("admin");
        Topic topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
        ExternalBlog blog = x.blog(f.user("member"), topic, ExternalBlogStatus.ACTIVE);
        blog.markVerified(JpaFixtures.T0);
        post = x.post(blog, "Post", topic, null);
        em.flush();
        events = mock(ApplicationEventPublisher.class);
        service = new AdminExternalPostService(postRepository, exclusionRepository, userRepository,
                new AdminAuditService(auditRepository, userRepository), events);
    }

    private List<AdminAuditLog> audits(String action) {
        return em.createQuery("select a from AdminAuditLog a where a.action = :action order by a.id",
                AdminAuditLog.class).setParameter("action", action).getResultList();
    }

    @Test
    void removeIsFinalAndAudited() {
        AdminExternalPostResponse removed = service.remove(admin.getId(), post.getId(), " 저작권 ", IP);

        assertThat(removed.base().status()).isEqualTo(ExternalPostStatus.REMOVED);
        assertThat(removed.base().removedReason()).isEqualTo(RemovedReason.ADMIN);
        AdminAuditLog log = audits(AuditActions.EXTERNAL_POST_REMOVE).getFirst();
        assertThat(log.getTargetType()).isEqualTo(AuditActions.TARGET_EXTERNAL_POST);
        assertThat(log.getTargetId()).isEqualTo(post.getId());
        assertThat(log.getReason()).isEqualTo("저작권");
        verify(events).publishEvent(new PortalChangedEvent("external:remove-post"));

        BusinessException again = catchThrowableOfType(BusinessException.class,
                () -> service.remove(admin.getId(), post.getId(), "again", IP));
        assertThat(again.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT);
        assertThat(again.params()).containsEntry("status", "REMOVED");
        BusinessException noReason = catchThrowableOfType(BusinessException.class,
                () -> service.remove(admin.getId(), post.getId(), "", IP));
        assertThat(noReason.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        BusinessException missing = catchThrowableOfType(BusinessException.class,
                () -> service.remove(admin.getId(), 999_999L, "x", IP));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.EXTERNAL_POST_NOT_FOUND);
    }

    @Test
    void reportRemovalIsIdempotent() {
        service.removeForReport(admin.getId(), post.getId(), "report #1", IP);
        service.removeForReport(admin.getId(), post.getId(), "report #2", IP);

        em.flush();
        em.clear();
        assertThat(postRepository.findById(post.getId()).orElseThrow().getRemovedReason())
                .isEqualTo(RemovedReason.REPORT);
        assertThat(audits(AuditActions.EXTERNAL_POST_REMOVE)).hasSize(1);
        BusinessException missing = catchThrowableOfType(BusinessException.class,
                () -> service.removeForReport(admin.getId(), 999_999L, "x", IP));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND);
    }

    @Test
    void excludeIsIdempotentAndUnexcludeNeedsExclusion() {
        ExternalExclusionResponse first = service.exclude(admin.getId(), post.getId(), "광고", IP);
        assertThat(first.externalPostId()).isEqualTo(post.getId());
        assertThat(first.reason()).isEqualTo("광고");
        assertThat(first.excludedBy().userId()).isEqualTo(admin.getId());
        ExternalExclusionResponse changed = service.exclude(admin.getId(), post.getId(), "중복", IP);
        assertThat(changed.reason()).isEqualTo("중복");
        assertThat(exclusionRepository.findByExternalPostIds(List.of(post.getId()))).hasSize(1);
        List<AdminAuditLog> excludes = audits(AuditActions.PORTAL_EXCLUDE);
        assertThat(excludes).hasSize(2);
        assertThat(excludes.get(1).getTargetType()).isEqualTo(AuditActions.TARGET_EXTERNAL_POST);
        assertThat(excludes.get(1).getBefore()).containsEntry("reason", "광고");
        assertThat(excludes.get(1).getAfter()).containsEntry("reason", "중복");

        service.unexclude(admin.getId(), post.getId(), IP);
        assertThat(exclusionRepository.findByExternalPostId(post.getId())).isEmpty();
        assertThat(audits(AuditActions.PORTAL_UNEXCLUDE)).singleElement()
                .satisfies(log -> assertThat(log.getBefore()).containsEntry("reason", "중복"));
        verify(events).publishEvent(new PortalChangedEvent("external:unexclude"));
        BusinessException none = catchThrowableOfType(BusinessException.class,
                () -> service.unexclude(admin.getId(), post.getId(), IP));
        assertThat(none.errorCode()).isEqualTo(ErrorCode.PORTAL_EXCLUSION_NOT_FOUND);
        BusinessException missing = catchThrowableOfType(BusinessException.class,
                () -> service.exclude(admin.getId(), 999_999L, "x", IP));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.EXTERNAL_POST_NOT_FOUND);
    }
}
