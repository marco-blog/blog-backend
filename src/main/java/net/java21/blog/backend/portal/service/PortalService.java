package net.java21.blog.backend.portal.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.dto.LatestSection;
import net.java21.blog.backend.portal.dto.NewBlogResponse;
import net.java21.blog.backend.portal.dto.PopularTagResponse;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.dto.PortalHomeResponse;
import net.java21.blog.backend.portal.repository.PortalCardQueryRepository;
import net.java21.blog.backend.portal.repository.PortalCardRow;
import net.java21.blog.backend.portal.repository.PortalSectionQueryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포털 메인(003 FR-034·080·085~088·090, research P5·P6). 결과는 {@link PortalCache}에 키 {@code HOME}·
 * {@code LATEST:{source}:{cursor}}·{@code POPULARITY}로 담는다(기본 5분). 메인 영역(인기·최신)은 같은 블로그 2편까지, 추천에는 제한이
 * 없다. 007부터 인기·최신에 외부 글({@link ExternalPortalSource})이 섞인다(research E13): 두 출처를 (발행 시각 내림, INTERNAL 먼저,
 * id 내림)으로 합친 뒤 2편 제한을 키 {@code P:{blogId}}·{@code E:{externalBlogId}}로 적용한다. 추천·인기 태그·새 블로그는 내부만.
 */
@Service
public class PortalService {

    public static final int CURATION_LIMIT = 5;
    public static final int POPULAR_LIMIT = 12;
    public static final int LATEST_LIMIT = 20;
    public static final int POPULAR_TAG_LIMIT = 20;
    public static final int NEW_BLOG_LIMIT = 6;
    /** 최신 글 한 묶음을 채우려고 살펴보는 행 수의 상한(2편 제한으로 건너뛰는 행 포함). 다 보고도 차지 않으면 거기까지 준다. */
    static final int LATEST_WINDOW = LATEST_LIMIT * 5;

    private final PortalCache cache;
    private final PortalCriteriaFactory criteriaFactory;
    private final PortalCardQueryRepository cards;
    private final PortalSectionQueryRepository sections;
    private final PopularityCalculator popularityCalculator;
    private final PortalProperties properties;
    private final ExternalPortalSource external;

    public PortalService(PortalCache cache, PortalCriteriaFactory criteriaFactory, PortalCardQueryRepository cards,
            PortalSectionQueryRepository sections, PopularityCalculator popularityCalculator,
            PortalProperties properties) {
        this(cache, criteriaFactory, cards, sections, popularityCalculator, properties, ExternalPortalSource.NONE);
    }

    @Autowired
    public PortalService(PortalCache cache, PortalCriteriaFactory criteriaFactory, PortalCardQueryRepository cards,
            PortalSectionQueryRepository sections, PopularityCalculator popularityCalculator,
            PortalProperties properties, ExternalPortalSource external) {
        this.cache = cache;
        this.criteriaFactory = criteriaFactory;
        this.cards = cards;
        this.sections = sections;
        this.popularityCalculator = popularityCalculator;
        this.properties = properties;
        this.external = external;
    }

    @Transactional(readOnly = true)
    public PortalHomeResponse home() {
        return cache.get("HOME", () -> {
            PortalCriteria criteria = criteriaFactory.now();
            Instant now = criteria.now();
            List<PortalCardResponse> curations = toCards(
                    cards.findByIds(sections.findActiveCurationPostIds(criteria, now, CURATION_LIMIT), criteria));
            List<PortalCardResponse> popular = popularCards(criteria);
            LatestSection latest = latestSection(criteria, null, PortalSourceFilter.ALL);
            List<PopularTagResponse> tags = sections
                    .findPopularTags(criteria, now.minus(properties.popularWindow()), POPULAR_TAG_LIMIT)
                    .stream().map(PopularTagResponse::from).toList();
            List<NewBlogResponse> newBlogs = sections
                    .findNewBlogs(criteria, now.minus(properties.topicCountWindow()), NEW_BLOG_LIMIT)
                    .stream().map(NewBlogResponse::from).toList();
            return new PortalHomeResponse(curations, popular, latest, tags, newBlogs, now);
        });
    }

    /** 커서 다음의 최신 글 묶음(두 출처). 커서 형식이 틀리면 400 {@code VALIDATION_FAILED}(field {@code cursor}). */
    @Transactional(readOnly = true)
    public LatestSection latest(String cursor) {
        return latest(cursor, null);
    }

    /**
     * 커서 다음의 최신 글 묶음(007 {@code source=all|internal|external}, 기본 all). 커서와 필터가 달라도 커서 위치부터 그 필터로 읽는다.
     * 잘못된 {@code source}는 400 {@code VALIDATION_FAILED}(field {@code source}).
     */
    @Transactional(readOnly = true)
    public LatestSection latest(String cursor, String source) {
        PortalSourceFilter filter = PortalSourceFilter.parse(source);
        PortalCursor.Position after = PortalCursor.decode(cursor);
        String key = "LATEST:" + filter.key() + ":" + (after == null ? "" : cursor);
        return cache.get(key, () -> latestSection(criteriaFactory.now(), after, filter));
    }

    /** 인기 점수 스냅숏(캐시 항목 {@code POPULARITY}). 주제 페이지 인기순도 이것을 쓴다. */
    @Transactional(readOnly = true)
    public PopularitySnapshot popularity() {
        return cache.get("POPULARITY", () -> popularityCalculator.calculate(criteriaFactory.now()));
    }

    private List<PortalCardResponse> popularCards(PortalCriteria criteria) {
        // 캐시가 뒤에서 다시 부르는 계산 함수는 요청의 기준 시각(criteria)을 붙잡지 않는다(popularity()와 같은 함수).
        PopularitySnapshot snapshot = popularity();
        List<PopularitySnapshot.Entry> picked = PerBlogCap.apply(snapshot.entries(), PopularitySnapshot.Entry::capKey,
                PerBlogCap.PER_BLOG, POPULAR_LIMIT).items();
        return cardsFor(picked.stream().map(e -> new PortalKey(e.source(), e.postId(), e.publishedAt())).toList(),
                criteria, cards, external);
    }

    /**
     * 출처가 섞인 키 목록의 카드를 그 순서대로(노출이 아니게 된 글은 빠짐). 내부·외부 각각 쿼리 1회(그 출처 키가 있을 때만).
     */
    static List<PortalCardResponse> cardsFor(List<PortalKey> keys, PortalCriteria criteria,
            PortalCardQueryRepository cards, ExternalPortalSource external) {
        List<Long> internalIds = new ArrayList<>();
        List<Long> externalIds = new ArrayList<>();
        for (PortalKey key : keys) {
            (key.source() == PortalSourceType.INTERNAL ? internalIds : externalIds).add(key.id());
        }
        Map<String, PortalCardResponse> byKey = new HashMap<>();
        if (!internalIds.isEmpty()) {
            for (PortalCardRow row : cards.findByIds(internalIds, criteria)) {
                byKey.put(PortalSourceType.INTERNAL.code() + row.id(), PortalCardResponse.from(row));
            }
        }
        if (!externalIds.isEmpty()) {
            for (PortalItem item : external.cards(externalIds, criteria.now())) {
                byKey.put(PortalSourceType.EXTERNAL.code() + item.id(), item.card());
            }
        }
        List<PortalCardResponse> out = new ArrayList<>(keys.size());
        for (PortalKey key : keys) {
            PortalCardResponse card = byKey.get(key.source().code() + key.id());
            if (card != null) {
                out.add(card);
            }
        }
        return out;
    }

    private LatestSection latestSection(PortalCriteria criteria, PortalCursor.Position after,
            PortalSourceFilter filter) {
        List<PortalItem> rows = new ArrayList<>();
        if (filter.includes(PortalSourceType.INTERNAL)) {
            cards.findLatest(criteria, after, LATEST_WINDOW + 1).forEach(row -> rows.add(PortalItem.internal(row)));
        }
        if (filter.includes(PortalSourceType.EXTERNAL) && external.enabled()) {
            rows.addAll(external.latest(criteria.now(), after, LATEST_WINDOW + 1));
            rows.sort(PortalItem.LATEST_ORDER);
        }
        List<PortalItem> window = rows.subList(0, Math.min(rows.size(), LATEST_WINDOW + 1));
        PerBlogCap.Result<PortalItem> capped = PerBlogCap.apply(
                window.subList(0, Math.min(window.size(), LATEST_WINDOW)), PortalItem::capKey, PerBlogCap.PER_BLOG,
                LATEST_LIMIT);
        boolean more = capped.examined() < window.size();
        String next = more && capped.lastExamined() != null
                ? PortalCursor.encode(capped.lastExamined().position())
                : null;
        return new LatestSection(capped.items().stream().map(PortalItem::card).toList(), next);
    }

    static List<PortalCardResponse> toCards(List<PortalCardRow> rows) {
        return rows.stream().map(PortalCardResponse::from).toList();
    }
}
