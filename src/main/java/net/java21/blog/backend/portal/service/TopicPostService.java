package net.java21.blog.backend.portal.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.repository.PortalCardQueryRepository;
import net.java21.blog.backend.portal.repository.TopicPostQueryRepository;
import net.java21.blog.backend.topic.service.TopicPage;
import net.java21.blog.backend.topic.service.TopicService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주제 페이지 글 목록(003 FR-078, research P8, 결정 표 11번). {@code latest}는 발행 최신순, {@code popular}는 인기 점수 스냅숏을
 * 주제로 거른 순서(점수가 있는 글만)다. 같은 블로그 2편 제한은 없다. 결과는 포털 캐시
 * {@code TOPIC:{id}:{sort}:{source}:{page}:{size}}.
 * <p>007(research E13): {@code source=all|internal|external}(기본 all). {@code latest}는 두 출처에서 (id, 발행 시각)만
 * {@code (page+1) × size}행씩 읽어 합치고 그 페이지 몫만 카드로 읽는다. {@code totalCount}는 두 출처 수의 합. 깊이 비용 때문에
 * {@code page}가 {@value #MAX_PAGE}를 넘으면 빈 목록이다({@code totalCount}는 그대로). 외부 출처가 없으면(또는 {@code internal}) 003과
 * 같은 쿼리다.
 */
@Service
public class TopicPostService {

    public static final List<String> SORTS = List.of("latest", "popular");
    /** 합치기 깊이 상한(0부터 센 페이지 번호). */
    public static final int MAX_PAGE = 49;

    private final TopicService topicService;
    private final TopicPostQueryRepository topicPosts;
    private final PortalCardQueryRepository cards;
    private final PortalService portalService;
    private final PortalCriteriaFactory criteriaFactory;
    private final PortalCache cache;
    private final ExternalPortalSource external;

    public TopicPostService(TopicService topicService, TopicPostQueryRepository topicPosts,
            PortalCardQueryRepository cards, PortalService portalService, PortalCriteriaFactory criteriaFactory,
            PortalCache cache) {
        this(topicService, topicPosts, cards, portalService, criteriaFactory, cache, ExternalPortalSource.NONE);
    }

    @Autowired
    public TopicPostService(TopicService topicService, TopicPostQueryRepository topicPosts,
            PortalCardQueryRepository cards, PortalService portalService, PortalCriteriaFactory criteriaFactory,
            PortalCache cache, ExternalPortalSource external) {
        this.topicService = topicService;
        this.topicPosts = topicPosts;
        this.cards = cards;
        this.portalService = portalService;
        this.criteriaFactory = criteriaFactory;
        this.cache = cache;
        this.external = external;
    }

    /** {@code source=all}. */
    @Transactional(readOnly = true)
    public Page<PortalCardResponse> posts(String slug, String sort, Pageable pageable) {
        return posts(slug, sort, null, pageable);
    }

    /**
     * @param sort   {@code latest}(기본)·{@code popular}. 다른 값이면 400 {@code VALIDATION_FAILED}(field {@code sort},
     *               {@code INVALID}, {@code params.allowed})
     * @param source {@code all}(기본)·{@code internal}·{@code external}. 다른 값이면 400(field {@code source})
     */
    @Transactional(readOnly = true)
    public Page<PortalCardResponse> posts(String slug, String sort, String source, Pageable pageable) {
        String resolved = resolveSort(sort);
        PortalSourceFilter filter = PortalSourceFilter.parse(source);
        TopicPage topic = topicService.requirePage(slug);
        String key = "TOPIC:" + topic.topicId() + ":" + resolved + ":" + filter.key() + ":"
                + pageable.getPageNumber() + ":" + pageable.getPageSize();
        return cache.get(key, () -> "popular".equals(resolved) ? popular(topic, filter, pageable)
                : latest(topic, filter, pageable));
    }

    static String resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "latest";
        }
        String value = sort.strip().toLowerCase(Locale.ROOT);
        if (!SORTS.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid sort: " + sort,
                    List.of(new FieldError("sort", "INVALID", Map.of("allowed", SORTS))));
        }
        return value;
    }

    private Page<PortalCardResponse> latest(TopicPage topic, PortalSourceFilter filter, Pageable pageable) {
        PortalCriteria criteria = criteriaFactory.now();
        if (!external.enabled() && filter == PortalSourceFilter.EXTERNAL) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        if (filter == PortalSourceFilter.INTERNAL || !external.enabled()) {
            return topicPosts.findLatest(criteria, topic.topicIds(), pageable).map(PortalCardResponse::from);
        }
        boolean internal = filter.includes(PortalSourceType.INTERNAL);
        long total = (internal ? topicPosts.count(criteria, topic.topicIds()) : 0)
                + external.topicCount(criteria.now(), topic.topicIds());
        if (pageable.getPageNumber() > MAX_PAGE || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        int depth = (int) (pageable.getOffset() + pageable.getPageSize());
        List<PortalKey> keys = new ArrayList<>();
        if (internal) {
            keys.addAll(topicPosts.findLatestKeys(criteria, topic.topicIds(), depth));
        }
        keys.addAll(external.topicKeys(criteria.now(), topic.topicIds(), depth));
        keys.sort(PortalKey.LATEST_ORDER);
        int from = (int) Math.min(pageable.getOffset(), keys.size());
        int to = Math.min(depth, keys.size());
        List<PortalCardResponse> content = from == to ? List.of()
                : PortalService.cardsFor(keys.subList(from, to), criteria, cards, external);
        return new PageImpl<>(content, pageable, total);
    }

    private Page<PortalCardResponse> popular(TopicPage topic, PortalSourceFilter filter, Pageable pageable) {
        List<PortalKey> ranked = portalService.popularity().forTopics(topic.topicIds(), filter).stream()
                .map(e -> new PortalKey(e.source(), e.postId(), e.publishedAt())).toList();
        int from = (int) Math.min(pageable.getOffset(), ranked.size());
        int to = Math.min(from + pageable.getPageSize(), ranked.size());
        List<PortalCardResponse> content = ranked.isEmpty() || from == to ? List.of()
                : PortalService.cardsFor(ranked.subList(from, to), criteriaFactory.now(), cards, external);
        return new PageImpl<>(content, pageable, ranked.size());
    }
}
