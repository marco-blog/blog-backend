package net.java21.blog.backend.admin.topic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.topic.dto.AdminTopicNode;
import net.java21.blog.backend.admin.topic.dto.CreateTopicRequest;
import net.java21.blog.backend.admin.topic.dto.TopicOrderRequest;
import net.java21.blog.backend.admin.topic.dto.UpdateTopicRequest;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.domain.TopicNames;
import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.topic.repository.TopicRow;
import net.java21.blog.backend.topic.service.TopicService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 주제 관리(003 FR-075, FR-079, FR-147, research P10). 추가·이름과 색 변경·숨김·탭 고정·순서 바꾸기. 삭제는 없다(숨긴다).
 * 모든 쓰기는 같은 트랜잭션에서 작업 기록을 남기고, 커밋 뒤 {@link PortalChangedEvent}로 포털 캐시를 비운다.
 */
@Service
public class AdminTopicService {

    static final int SLUG_MIN = 2;
    static final List<String> LANGUAGES = List.of("ko", "en", "ja", TopicNames.ZH_CN);

    private final TopicRepository topicRepository;
    private final TopicQueryRepository topicQueryRepository;
    private final TopicService topicService;
    private final SystemSettingsService settings;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher events;

    public AdminTopicService(TopicRepository topicRepository, TopicQueryRepository topicQueryRepository,
            TopicService topicService, SystemSettingsService settings, AdminAuditService auditService,
            ApplicationEventPublisher events) {
        this.topicRepository = topicRepository;
        this.topicQueryRepository = topicQueryRepository;
        this.topicService = topicService;
        this.settings = settings;
        this.auditService = auditService;
        this.events = events;
    }

    /** 숨김을 포함한 전체 트리(주제 쿼리 1회 + 캐시된 글 수). */
    @Transactional(readOnly = true)
    public List<AdminTopicNode> tree() {
        return buildTree(topicQueryRepository.findAll(), topicService.recentPostCounts(),
                settings.topicAutoHideThreshold());
    }

    /** 주제 추가: 같은 부모의 마지막 순서로. */
    @Transactional
    public AdminTopicNode create(long adminId, CreateTopicRequest request, String requestIp) {
        List<FieldError> errors = new ArrayList<>();
        String slug = request.slug() == null ? "" : request.slug().strip();
        if (slug.isEmpty()) {
            errors.add(FieldError.of("slug", "REQUIRED"));
        } else if (slug.length() < SLUG_MIN || slug.length() > Topic.SLUG_MAX || !Topic.SLUG.matcher(slug).matches()) {
            errors.add(new FieldError("slug", "INVALID_FORMAT", Map.of("min", SLUG_MIN, "max", Topic.SLUG_MAX)));
        }
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> sent = request.names() == null ? Map.of() : request.names();
        for (String language : LANGUAGES) {
            String name = validName(language, sent.get(language), errors);
            names.put(language, name);
        }
        unknownLanguages(sent, errors);
        String color = validColor(request.cardColor(), errors);
        throwIfAny(errors);

        Topic parent = null;
        if (request.parentId() != null) {
            parent = topicRepository.findById(request.parentId())
                    .orElseThrow(() -> topicNotFound(request.parentId()));
            if (!parent.isMajor()) {
                throw new BusinessException(ErrorCode.TOPIC_DEPTH_EXCEEDED, "Topics have two levels only");
            }
        }
        if (topicRepository.existsBySlug(slug)) {
            throw new BusinessException(ErrorCode.TOPIC_SLUG_TAKEN, "Topic slug taken: " + slug);
        }
        int sortOrder = topicRepository.findChildren(request.parentId()).stream()
                .mapToInt(Topic::getSortOrder).max().orElse(-1) + 1;
        Topic topic = topicRepository.saveAndFlush(new Topic(parent, slug, TopicNames.of(names), sortOrder, color,
                false));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("parentId", request.parentId());
        after.put("slug", slug);
        after.put("names", names);
        after.put("cardColor", color);
        after.put("sortOrder", sortOrder);
        auditService.record(adminId, AuditActions.TOPIC_CREATE, AuditActions.TARGET_TOPIC, topic.getId(), null,
                after, requestIp);
        changed("topic:create");
        return node(topic.getId());
    }

    /** 이름·색·숨김·고정 변경. 바뀐 항목마다 작업 기록 한 행. */
    @Transactional
    public AdminTopicNode update(long adminId, long id, UpdateTopicRequest request, String requestIp) {
        Topic topic = topicRepository.findById(id).orElseThrow(() -> topicNotFound(id));
        List<FieldError> errors = new ArrayList<>();
        Map<String, String> names = topic.namesByLanguage();
        if (request.hasNames()) {
            if (request.getNames() == null) {
                errors.add(FieldError.of("names", "REQUIRED"));
            } else {
                unknownLanguages(request.getNames(), errors);
                for (String language : LANGUAGES) {
                    if (request.getNames().containsKey(language)) {
                        names.put(language, validName(language, request.getNames().get(language), errors));
                    }
                }
            }
        }
        String color = topic.getCardColor();
        if (request.hasCardColor()) {
            color = validColor(request.getCardColor(), errors);
        }
        if (request.hasAdminHidden() && request.getAdminHidden() == null) {
            errors.add(FieldError.of("adminHidden", "REQUIRED"));
        }
        if (request.hasPinnedOnTab() && request.getPinnedOnTab() == null) {
            errors.add(FieldError.of("pinnedOnTab", "REQUIRED"));
        }
        throwIfAny(errors);

        boolean changed = false;
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (!names.equals(topic.namesByLanguage())) {
            before.put("names", topic.namesByLanguage());
            after.put("names", names);
            topic.rename(TopicNames.of(names));
        }
        if (!Objects.equals(color, topic.getCardColor())) {
            before.put("cardColor", topic.getCardColor());
            after.put("cardColor", color);
            topic.changeColor(color);
        }
        if (!after.isEmpty()) {
            auditService.record(adminId, AuditActions.TOPIC_UPDATE, AuditActions.TARGET_TOPIC, id, before, after,
                    requestIp);
            changed = true;
        }
        if (request.hasAdminHidden() && request.getAdminHidden() != topic.isAdminHidden()) {
            boolean hide = request.getAdminHidden();
            if (hide) {
                topic.hide();
            } else {
                topic.unhide();
            }
            auditService.record(adminId, hide ? AuditActions.TOPIC_HIDE : AuditActions.TOPIC_UNHIDE,
                    AuditActions.TARGET_TOPIC, id, Map.of("adminHidden", !hide), Map.of("adminHidden", hide), requestIp);
            changed = true;
        }
        if (request.hasPinnedOnTab() && request.getPinnedOnTab() != topic.isPinnedOnTab()) {
            boolean pin = request.getPinnedOnTab();
            if (pin) {
                topic.pin();
            } else {
                topic.unpin();
            }
            auditService.record(adminId, pin ? AuditActions.TOPIC_PIN : AuditActions.TOPIC_UNPIN,
                    AuditActions.TARGET_TOPIC, id, Map.of("pinnedOnTab", !pin), Map.of("pinnedOnTab", pin), requestIp);
            changed = true;
        }
        if (changed) {
            topicRepository.flush();
            changed("topic:update");
        }
        return node(id);
    }

    /** 한 부모의 자식 순서를 통째로 바꾼다. {@code ids}가 그 자식 전체(같은 집합, 중복 없음)가 아니면 400. */
    @Transactional
    public List<AdminTopicNode> reorder(long adminId, TopicOrderRequest request, String requestIp) {
        if (request.ids() == null) {
            throw invalid(List.of(FieldError.of("ids", "REQUIRED")));
        }
        if (request.parentId() != null && !topicRepository.existsById(request.parentId())) {
            throw topicNotFound(request.parentId());
        }
        List<Topic> children = topicRepository.findChildren(request.parentId());
        List<Long> before = children.stream().map(Topic::getId).toList();
        Set<Long> unique = new HashSet<>(request.ids());
        if (request.ids().size() != before.size() || unique.size() != before.size()
                || !unique.equals(new HashSet<>(before))) {
            throw invalid(List.of(FieldError.of("ids", "INVALID")));
        }
        Map<Long, Topic> byId = new LinkedHashMap<>();
        children.forEach(t -> byId.put(t.getId(), t));
        for (int i = 0; i < request.ids().size(); i++) {
            byId.get(request.ids().get(i)).moveTo(i);
        }
        if (!before.equals(request.ids())) {
            Map<String, Object> beforeValue = new LinkedHashMap<>();
            beforeValue.put("parentId", request.parentId());
            beforeValue.put("ids", before);
            Map<String, Object> afterValue = new LinkedHashMap<>();
            afterValue.put("parentId", request.parentId());
            afterValue.put("ids", List.copyOf(request.ids()));
            auditService.record(adminId, AuditActions.TOPIC_REORDER, AuditActions.TARGET_TOPIC, request.parentId(),
                    beforeValue, afterValue, requestIp);
            topicRepository.flush();
            changed("topic:reorder");
        }
        return tree();
    }

    /**
     * 관리자 트리를 만든다. {@code rows}는 대분류 → 소속 소분류 순이며 숨김을 포함한다. 탭 판단은 공개 트리와 같지만 운영자 숨김
     * (부모 포함) 주제는 탭에 보이지 않는다. 대분류의 글 수는 숨기지 않은 소분류의 합이다.
     */
    static List<AdminTopicNode> buildTree(List<TopicRow> rows, Map<Long, Long> counts, int threshold) {
        Map<Long, TopicRow> majors = new LinkedHashMap<>();
        Map<Long, List<AdminTopicNode>> children = new LinkedHashMap<>();
        Map<Long, Long> sums = new LinkedHashMap<>();
        for (TopicRow row : rows) {
            if (row.isMajor()) {
                majors.put(row.id(), row);
                children.put(row.id(), new ArrayList<>());
                sums.put(row.id(), 0L);
            }
        }
        for (TopicRow row : rows) {
            if (!row.isMajor() && majors.containsKey(row.parentId())) {
                long count = counts.getOrDefault(row.id(), 0L);
                boolean onTab = !row.effectiveHidden() && (row.pinnedOnTab() || count >= threshold);
                children.get(row.parentId()).add(new AdminTopicNode(row.id(), row.slug(), row.parentId(),
                        row.names().asMap(), row.cardColor(), onTab, List.of(), row.adminHidden(),
                        row.effectiveHidden(), row.pinnedOnTab(), count, row.createdAt(), row.updatedAt()));
                if (!row.adminHidden()) {
                    sums.merge(row.parentId(), count, Long::sum);
                }
            }
        }
        List<AdminTopicNode> tree = new ArrayList<>();
        for (TopicRow major : majors.values()) {
            List<AdminTopicNode> kids = List.copyOf(children.get(major.id()));
            long sum = sums.get(major.id());
            boolean onTab = !major.adminHidden() && (major.pinnedOnTab() || sum >= threshold
                    || kids.stream().anyMatch(AdminTopicNode::onTab));
            tree.add(new AdminTopicNode(major.id(), major.slug(), null, major.names().asMap(), major.cardColor(),
                    onTab, kids, major.adminHidden(), major.adminHidden(), major.pinnedOnTab(), sum,
                    major.createdAt(), major.updatedAt()));
        }
        return List.copyOf(tree);
    }

    private AdminTopicNode node(long id) {
        for (AdminTopicNode major : tree()) {
            if (major.id() == id) {
                return major;
            }
            for (AdminTopicNode minor : major.children()) {
                if (minor.id() == id) {
                    return minor;
                }
            }
        }
        throw topicNotFound(id);
    }

    private void changed(String reason) {
        events.publishEvent(new PortalChangedEvent(reason));
    }

    private static String validName(String language, String value, List<FieldError> errors) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty()) {
            errors.add(FieldError.of("names." + language, "REQUIRED"));
        } else if (name.length() > Topic.NAME_MAX) {
            errors.add(new FieldError("names." + language, "TOO_LONG", Map.of("max", Topic.NAME_MAX)));
        }
        return name;
    }

    private static void unknownLanguages(Map<String, String> names, List<FieldError> errors) {
        names.keySet().stream().filter(key -> !LANGUAGES.contains(key)).sorted()
                .forEach(key -> errors.add(FieldError.of("names." + key, "INVALID")));
    }

    private static String validColor(String value, List<FieldError> errors) {
        if (value == null) {
            return null;
        }
        if (!Topic.CARD_COLOR.matcher(value).matches()) {
            errors.add(FieldError.of("cardColor", "INVALID_FORMAT"));
        }
        return value;
    }

    private static void throwIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
    }

    private static BusinessException invalid(List<FieldError> errors) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", errors);
    }

    private static BusinessException topicNotFound(Long id) {
        return new BusinessException(ErrorCode.TOPIC_NOT_FOUND, "Topic not found: " + id);
    }
}
