package net.java21.blog.backend.portal.service;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주제 페이지 글 목록(003 FR-078, research P8, 결정 표 11번). {@code latest}는 발행 최신순, {@code popular}는 인기 점수 스냅숏을
 * 주제로 거른 순서(점수가 있는 글만)다. 같은 블로그 2편 제한은 없다. 결과는 포털 캐시 {@code TOPIC:{id}:{sort}:{page}:{size}}.
 */
@Service
public class TopicPostService {

    public static final List<String> SORTS = List.of("latest", "popular");

    private final TopicService topicService;
    private final TopicPostQueryRepository topicPosts;
    private final PortalCardQueryRepository cards;
    private final PortalService portalService;
    private final PortalCriteriaFactory criteriaFactory;
    private final PortalCache cache;

    public TopicPostService(TopicService topicService, TopicPostQueryRepository topicPosts,
            PortalCardQueryRepository cards, PortalService portalService, PortalCriteriaFactory criteriaFactory,
            PortalCache cache) {
        this.topicService = topicService;
        this.topicPosts = topicPosts;
        this.cards = cards;
        this.portalService = portalService;
        this.criteriaFactory = criteriaFactory;
        this.cache = cache;
    }

    /**
     * @param sort {@code latest}(기본)·{@code popular}. 다른 값이면 400 {@code VALIDATION_FAILED}(field {@code sort},
     *             {@code INVALID}, {@code params.allowed})
     */
    @Transactional(readOnly = true)
    public Page<PortalCardResponse> posts(String slug, String sort, Pageable pageable) {
        String resolved = resolveSort(sort);
        TopicPage topic = topicService.requirePage(slug);
        String key = "TOPIC:" + topic.topicId() + ":" + resolved + ":" + pageable.getPageNumber() + ":"
                + pageable.getPageSize();
        return cache.get(key, () -> "popular".equals(resolved) ? popular(topic, pageable) : latest(topic, pageable));
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

    private Page<PortalCardResponse> latest(TopicPage topic, Pageable pageable) {
        return topicPosts.findLatest(criteriaFactory.now(), topic.topicIds(), pageable)
                .map(PortalCardResponse::from);
    }

    private Page<PortalCardResponse> popular(TopicPage topic, Pageable pageable) {
        List<Long> ranked = portalService.popularity().forTopics(topic.topicIds()).stream()
                .map(PopularitySnapshot.Entry::postId).toList();
        int from = (int) Math.min(pageable.getOffset(), ranked.size());
        int to = Math.min(from + pageable.getPageSize(), ranked.size());
        List<PortalCardResponse> content = ranked.isEmpty() || from == to ? List.of()
                : PortalService.toCards(cards.findByIds(ranked.subList(from, to), criteriaFactory.now()));
        return new PageImpl<>(content, pageable, ranked.size());
    }
}
