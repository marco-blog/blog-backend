package net.java21.blog.backend.admin.external;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.classify.KeywordNormalizer;
import net.java21.blog.backend.external.domain.TopicMappingRule;
import net.java21.blog.backend.external.dto.TopicMappingRuleResponse;
import net.java21.blog.backend.external.repository.TopicMappingRuleRepository;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.service.TopicService;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주제 매핑 규칙(007 FR-121, US3 AS5). 키워드는 분류 사전·피드 태그와 같은 규칙({@link KeywordNormalizer})으로 정규화해 1~100자,
 * 정규화한 값이 이미 있으면 409 {@code TOPIC_MAPPING_RULE_KEYWORD_TAKEN}({@code ruleId}). 주제는 003 규칙(소분류, 숨김 아님),
 * 우선순위는 -1000~1000(기본 0). 바뀐 규칙은 다음 수집부터 적용되고(수집기는 피드마다 규칙을 새로 읽음) 이미 RULE로 정해진 글은 그대로다.
 * 모든 변경은 같은 트랜잭션에서 작업 기록(전후 값).
 */
@Service
public class AdminMappingRuleService {

    static final int QUERY_MAX = 100;

    private final TopicMappingRuleRepository repository;
    private final UserRepository userRepository;
    private final TopicService topicService;
    private final AdminAuditService auditService;

    public AdminMappingRuleService(TopicMappingRuleRepository repository, UserRepository userRepository,
            TopicService topicService, AdminAuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.topicService = topicService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<TopicMappingRuleResponse> list(String q, Pageable pageable) {
        String query = q == null || q.isBlank() ? null : KeywordNormalizer.normalize(q);
        if (query != null && query.length() > QUERY_MAX) {
            throw invalid(new FieldError("q", "TOO_LONG", Map.of("max", QUERY_MAX)));
        }
        return repository.search(query, pageable).map(TopicMappingRuleResponse::of);
    }

    @Transactional
    public TopicMappingRuleResponse create(long adminId, String keyword, Long topicId, Integer priority,
            String requestIp) {
        String normalized = requireKeyword(keyword);
        if (topicId == null) {
            throw invalid(FieldError.of("topicId", "REQUIRED"));
        }
        int value = requirePriority(priority == null ? 0 : priority);
        requireFree(normalized, null);
        Topic topic = topicService.requireSelectable(topicId, null);
        TopicMappingRule rule = new TopicMappingRule(normalized, topic, value, userRepository.getReferenceById(adminId));
        save(rule, normalized);
        auditService.record(adminId, AuditActions.TOPIC_MAPPING_RULE_CREATE, AuditActions.TARGET_TOPIC_MAPPING_RULE,
                rule.getId(), null, snapshot(rule), requestIp);
        return TopicMappingRuleResponse.of(rule);
    }

    @Transactional
    public TopicMappingRuleResponse update(long adminId, long id, String keyword, Long topicId, Integer priority,
            String requestIp) {
        TopicMappingRule rule = require(id);
        Map<String, Object> before = snapshot(rule);
        String normalized = keyword == null ? null : requireKeyword(keyword);
        if (normalized != null) {
            requireFree(normalized, id);
        }
        Integer value = priority == null ? null : requirePriority(priority);
        Topic topic = topicId == null ? null : topicService.requireSelectable(topicId, rule.getTopic().getId());
        rule.change(normalized, topic, value);
        save(rule, normalized == null ? rule.getKeyword() : normalized);
        auditService.record(adminId, AuditActions.TOPIC_MAPPING_RULE_UPDATE, AuditActions.TARGET_TOPIC_MAPPING_RULE,
                id, before, snapshot(rule), requestIp);
        return TopicMappingRuleResponse.of(rule);
    }

    @Transactional
    public void delete(long adminId, long id, String requestIp) {
        TopicMappingRule rule = require(id);
        Map<String, Object> before = snapshot(rule);
        repository.delete(rule);
        repository.flush();
        auditService.record(adminId, AuditActions.TOPIC_MAPPING_RULE_DELETE, AuditActions.TARGET_TOPIC_MAPPING_RULE,
                id, before, null, requestIp);
    }

    private TopicMappingRule require(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Topic mapping rule not found: " + id));
    }

    private void save(TopicMappingRule rule, String keyword) {
        try {
            repository.saveAndFlush(rule);
        } catch (DataIntegrityViolationException e) {
            throw taken(repository.findByKeyword(keyword).map(TopicMappingRule::getId).orElse(null));
        }
    }

    private void requireFree(String keyword, Long self) {
        repository.findByKeyword(keyword).filter(other -> !other.getId().equals(self)).ifPresent(other -> {
            throw taken(other.getId());
        });
    }

    static String requireKeyword(String raw) {
        String normalized = raw == null ? "" : KeywordNormalizer.normalize(raw);
        if (normalized.isEmpty()) {
            throw invalid(FieldError.of("keyword", "REQUIRED"));
        }
        if (normalized.length() > TopicMappingRule.KEYWORD_MAX) {
            throw invalid(new FieldError("keyword", "TOO_LONG", Map.of("max", TopicMappingRule.KEYWORD_MAX)));
        }
        return normalized;
    }

    static int requirePriority(int priority) {
        if (priority < TopicMappingRule.PRIORITY_MIN || priority > TopicMappingRule.PRIORITY_MAX) {
            throw invalid(new FieldError("priority", "INVALID",
                    Map.of("min", TopicMappingRule.PRIORITY_MIN, "max", TopicMappingRule.PRIORITY_MAX)));
        }
        return priority;
    }

    private static Map<String, Object> snapshot(TopicMappingRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("keyword", rule.getKeyword());
        map.put("topicId", rule.getTopic().getId());
        map.put("priority", rule.getPriority());
        return map;
    }

    private static BusinessException taken(Long ruleId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ruleId", ruleId);
        return BusinessException.withParams(ErrorCode.TOPIC_MAPPING_RULE_KEYWORD_TAKEN, "Keyword already mapped",
                params);
    }

    static BusinessException invalid(FieldError error) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(error));
    }
}
