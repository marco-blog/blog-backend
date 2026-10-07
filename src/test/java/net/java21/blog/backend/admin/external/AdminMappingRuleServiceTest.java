package net.java21.blog.backend.admin.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.admin.audit.AdminAuditLog;
import net.java21.blog.backend.admin.audit.AdminAuditLogRepository;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.classify.ClassificationResult;
import net.java21.blog.backend.external.classify.TopicClassifier;
import net.java21.blog.backend.external.classify.TopicDecider;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.TopicMappingRuleResponse;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.fetch.TopicAssigner;
import net.java21.blog.backend.external.repository.TopicMappingRuleRepository;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.topic.service.TopicService;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** 007 T063: 매핑 규칙 관리(US3 AS5, FR-121). 저장소는 H2. */
@JpaRepositoryTest
class AdminMappingRuleServiceTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private TopicMappingRuleRepository ruleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TopicRepository topicRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;
    @Autowired
    private JPAQueryFactory queryFactory;

    private AdminMappingRuleService service;
    private User admin;
    private Topic major;
    private Topic it;
    private Topic science;
    private Topic hidden;

    @BeforeEach
    void setUp() {
        JpaFixtures f = new JpaFixtures(em);
        admin = f.user("admin");
        major = f.topic(null, "knowledge", 1);
        it = f.topic(major, "it-internet", 1);
        science = f.topic(major, "science", 2);
        hidden = f.topic(major, "hidden", 3);
        em.createQuery("update Topic t set t.adminHidden = true where t.id = :id").setParameter("id", hidden.getId())
                .executeUpdate();
        em.clear();
        TopicService topics = new TopicService(topicRepository, null, null, null, null, null,
                PortalProperties.defaults());
        service = new AdminMappingRuleService(ruleRepository, userRepository, topics,
                new AdminAuditService(auditLogRepository, userRepository));
    }

    private List<AdminAuditLog> logs(long id) {
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("TOPIC_MAPPING_RULE", id);
    }

    private BusinessException error(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("no error");
    }

    @Test
    void createNormalizesKeywordAndRecordsAudit() {
        TopicMappingRuleResponse created = service.create(admin.getId(), "  ＳＰＲＩＮＧ   Boot ", it.getId(), null,
                "127.0.0.1");

        assertThat(created.keyword()).isEqualTo("spring boot");
        assertThat(created.priority()).isZero();
        assertThat(created.topicId()).isEqualTo(it.getId());
        assertThat(created.createdBy().nickname()).isEqualTo("admin");
        AdminAuditLog log = logs(created.id()).getFirst();
        assertThat(log.getAction()).isEqualTo("TOPIC_MAPPING_RULE_CREATE");
        assertThat(log.getAfter()).containsEntry("keyword", "spring boot").containsEntry("priority", 0);

        BusinessException taken = error(() -> service.create(admin.getId(), "Spring Boot", science.getId(), 5, null));
        assertThat(taken.errorCode()).isEqualTo(ErrorCode.TOPIC_MAPPING_RULE_KEYWORD_TAKEN);
        assertThat(taken.params()).containsEntry("ruleId", created.id());
    }

    @Test
    void validatesKeywordTopicAndPriority() {
        assertThat(error(() -> service.create(admin.getId(), "   ", it.getId(), 0, null)).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(error(() -> service.create(admin.getId(), null, it.getId(), 0, null)).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        BusinessException tooLong = error(() -> service.create(admin.getId(), "a".repeat(101), it.getId(), 0, null));
        assertThat(tooLong.fieldErrors().getFirst().code()).isEqualTo("TOO_LONG");
        assertThat(service.create(admin.getId(), "b".repeat(100), it.getId(), 1000, null).priority()).isEqualTo(1000);
        assertThat(error(() -> service.create(admin.getId(), "x", it.getId(), 1001, null)).fieldErrors().getFirst()
                .field()).isEqualTo("priority");
        assertThat(error(() -> service.create(admin.getId(), "x", it.getId(), -1001, null)).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(error(() -> service.create(admin.getId(), "x", null, 0, null)).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(error(() -> service.create(admin.getId(), "x", major.getId(), 0, null)).errorCode())
                .isEqualTo(ErrorCode.TOPIC_NOT_SELECTABLE);
        assertThat(error(() -> service.create(admin.getId(), "x", hidden.getId(), 0, null)).errorCode())
                .isEqualTo(ErrorCode.TOPIC_NOT_SELECTABLE);
        assertThat(error(() -> service.create(admin.getId(), "x", 999_999L, 0, null)).errorCode())
                .isEqualTo(ErrorCode.TOPIC_NOT_FOUND);
    }

    @Test
    void updateDeleteAndListWithAudit() {
        long a = service.create(admin.getId(), "physics", it.getId(), 1, null).id();
        long b = service.create(admin.getId(), "chemistry", science.getId(), 5, null).id();
        long c = service.create(admin.getId(), "biology", science.getId(), 1, null).id();

        TopicMappingRuleResponse updated = service.update(admin.getId(), a, "Physics!", science.getId(), 7, null);
        assertThat(updated.keyword()).isEqualTo("physics!");
        assertThat(updated.topicId()).isEqualTo(science.getId());
        AdminAuditLog log = logs(a).getFirst();
        assertThat(log.getAction()).isEqualTo("TOPIC_MAPPING_RULE_UPDATE");
        assertThat(log.getBefore()).containsEntry("keyword", "physics").containsEntry("priority", 1);
        assertThat(log.getAfter()).containsEntry("keyword", "physics!").containsEntry("priority", 7);
        assertThat(service.update(admin.getId(), a, null, null, null, null).keyword()).isEqualTo("physics!");
        assertThat(service.update(admin.getId(), a, "physics!", null, null, null).priority()).isEqualTo(7);
        assertThat(error(() -> service.update(admin.getId(), a, "Chemistry", null, null, null)).errorCode())
                .isEqualTo(ErrorCode.TOPIC_MAPPING_RULE_KEYWORD_TAKEN);
        assertThat(error(() -> service.update(admin.getId(), 999_999L, "x", null, null, null)).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        assertThat(service.list(null, PageRequest.of(0, 20)).getContent()).extracting(TopicMappingRuleResponse::id)
                .containsExactly(a, b, c);
        assertThat(service.list("  CHEM ", PageRequest.of(0, 20)).getContent())
                .extracting(TopicMappingRuleResponse::id).containsExactly(b);
        assertThat(error(() -> service.list("q".repeat(101), PageRequest.of(0, 20))).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        service.delete(admin.getId(), c, null);
        assertThat(ruleRepository.findById(c)).isEmpty();
        assertThat(logs(c).getFirst().getAction()).isEqualTo("TOPIC_MAPPING_RULE_DELETE");
        assertThat(logs(c).getFirst().getBefore()).containsEntry("keyword", "biology");
        assertThat(error(() -> service.delete(admin.getId(), c, null)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void newRulesApplyFromNextCollectionAndDecidedPostsStay() {
        ExternalFixtures x = new ExternalFixtures(em);
        Topic topicIt = em.find(Topic.class, it.getId());
        ExternalPost ruled = x.post(x.blog(null, topicIt, ExternalBlogStatus.ACTIVE), "Old", topicIt, null);
        ruled.changeTopic(topicIt, TopicSource.RULE, Instant.EPOCH);
        em.flush();
        SystemSettingsService settings = mock(SystemSettingsService.class);
        when(settings.autoClassifyMinConfidence()).thenReturn(0.7);
        TopicClassifier none = input -> ClassificationResult.none("v");
        TopicDecider decider = new TopicDecider(ruleRepository, new TopicQueryRepository(queryFactory), none,
                settings);
        FeedItem item = new FeedItem("g", "https://e.example/1", "Title", null, null, Instant.EPOCH,
                List.of("Quantum"));

        assertThat(decider.start().decide(item, it.getId()).source()).isEqualTo(TopicSource.DEFAULT);
        long rule = service.create(admin.getId(), "quantum", science.getId(), 0, null).id();
        TopicAssigner.Decision decision = decider.start().decide(item, it.getId());
        assertThat(decision.source()).isEqualTo(TopicSource.RULE);
        assertThat(decision.topicId()).isEqualTo(science.getId());

        service.delete(admin.getId(), rule, null);
        em.flush();
        em.clear();
        ExternalPost stays = em.find(ExternalPost.class, ruled.getId());
        assertThat(stays.getTopicSource()).isEqualTo(TopicSource.RULE);
        assertThat(stays.getTopic().getId()).isEqualTo(it.getId());
    }
}
