package net.java21.blog.backend.topic.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.repository.TopicPostCountQueryRepository;
import net.java21.blog.backend.portal.service.PortalCache;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.portal.service.PortalCriteriaFactory;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.dto.TopicNode;
import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.topic.repository.TopicRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주제(003 FR-075~079, FR-147, research P2).
 * <ul>
 *   <li>공개 트리: 운영자 숨김이 아닌 주제 + 주제 탭 판단({@code onTab}). 트리와 최근 30일 글 수는 포털 캐시(5분)에 둔다.</li>
 *   <li>탭 판단(저장하지 않음, 결정 표 7번): 소분류는 고정이거나 최근 글 수 ≥ 기준, 대분류는 고정이거나 소속 합 ≥ 기준이거나
 *       탭에 보이는 소분류가 있을 때. 기준 0이면 모두 보인다.</li>
 *   <li>선택 가능 검사: 소분류이고 자신·부모가 운영자 숨김이 아니어야 한다. 지금 값과 같으면 검사하지 않는다(나중에 숨겨진 주제 유지).</li>
 * </ul>
 */
@Service
public class TopicService {

    static final String TREE_KEY = "TOPIC_TREE";
    static final String COUNTS_KEY = "TOPIC_COUNTS";

    private final TopicRepository topicRepository;
    private final TopicQueryRepository topicQueryRepository;
    private final TopicPostCountQueryRepository countRepository;
    private final PortalCache cache;
    private final PortalCriteriaFactory criteriaFactory;
    private final SystemSettingsService settings;
    private final PortalProperties properties;

    public TopicService(TopicRepository topicRepository, TopicQueryRepository topicQueryRepository,
            TopicPostCountQueryRepository countRepository, PortalCache cache, PortalCriteriaFactory criteriaFactory,
            SystemSettingsService settings, PortalProperties properties) {
        this.topicRepository = topicRepository;
        this.topicQueryRepository = topicQueryRepository;
        this.countRepository = countRepository;
        this.cache = cache;
        this.criteriaFactory = criteriaFactory;
        this.settings = settings;
        this.properties = properties;
    }

    /** 공개 주제 트리(GET /topics). */
    @Transactional(readOnly = true)
    public List<TopicNode> publicTree() {
        return cache.get(TREE_KEY, () -> buildTree(topicQueryRepository.findVisible(), recentPostCounts(),
                settings.topicAutoHideThreshold()));
    }

    /** 소분류별 최근({@code blog.portal.topic-count-window}, 30일) 포털 노출 글 수. 캐시. */
    @Transactional(readOnly = true)
    public Map<Long, Long> recentPostCounts() {
        return cache.get(COUNTS_KEY, () -> {
            PortalCriteria criteria = criteriaFactory.now();
            Instant since = criteria.now().minus(properties.topicCountWindow());
            return Map.copyOf(countRepository.countRecentByTopic(criteria, since));
        });
    }

    /**
     * 글·블로그 기본 주제로 고를 수 있는 주제를 돌려준다(FR-076·077).
     *
     * @param id        고른 주제 id. null(주제 없음)이면 null
     * @param currentId 지금 저장된 주제 id. 같은 값이면 검사하지 않는다
     * @throws BusinessException 없으면 404 {@code TOPIC_NOT_FOUND}, 대분류·운영자 숨김(부모 포함)이면 422 {@code TOPIC_NOT_SELECTABLE}
     */
    @Transactional(readOnly = true)
    public Topic requireSelectable(Long id, Long currentId) {
        if (id == null) {
            return null;
        }
        if (Objects.equals(id, currentId)) {
            return topicRepository.getReferenceById(id);
        }
        Topic topic = topicRepository.findWithParent(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOPIC_NOT_FOUND, "Topic not found: " + id));
        if (topic.isMajor() || topic.isEffectivelyHidden()) {
            throw new BusinessException(ErrorCode.TOPIC_NOT_SELECTABLE, "Topic not selectable: " + id);
        }
        return topic;
    }

    /**
     * 주제 페이지가 보여줄 주제(FR-078). 없거나 운영자 숨김(부모 포함)이면 404 {@code TOPIC_NOT_FOUND}. 자동 숨김은 영향이 없다.
     */
    @Transactional(readOnly = true)
    public TopicPage requirePage(String slug) {
        TopicRow row = topicQueryRepository.findBySlug(slug)
                .filter(r -> !r.effectiveHidden())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOPIC_NOT_FOUND, "Topic not found: " + slug));
        List<Long> ids = row.isMajor() ? topicQueryRepository.findVisibleChildIds(row.id()) : List.of(row.id());
        return new TopicPage(row.id(), row.slug(), row.isMajor(), List.copyOf(ids));
    }

    /**
     * 트리를 만들고 탭 판단을 붙인다. {@code rows}는 대분류 → 소속 소분류 순이며 숨김이 이미 빠져 있다.
     */
    static List<TopicNode> buildTree(List<TopicRow> rows, Map<Long, Long> counts, int threshold) {
        Map<Long, TopicRow> majors = new LinkedHashMap<>();
        Map<Long, List<TopicNode>> children = new LinkedHashMap<>();
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
                boolean onTab = row.pinnedOnTab() || count >= threshold;
                children.get(row.parentId()).add(new TopicNode(row.id(), row.slug(), row.parentId(),
                        row.names().asMap(), row.cardColor(), onTab, List.of()));
                sums.merge(row.parentId(), count, Long::sum);
            }
        }
        List<TopicNode> tree = new ArrayList<>();
        for (TopicRow major : majors.values()) {
            List<TopicNode> kids = List.copyOf(children.get(major.id()));
            boolean onTab = major.pinnedOnTab() || sums.get(major.id()) >= threshold
                    || kids.stream().anyMatch(TopicNode::onTab);
            tree.add(new TopicNode(major.id(), major.slug(), null, major.names().asMap(), major.cardColor(), onTab,
                    kids));
        }
        return List.copyOf(tree);
    }
}
