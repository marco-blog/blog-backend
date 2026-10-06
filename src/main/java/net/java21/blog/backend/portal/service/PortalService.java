package net.java21.blog.backend.portal.service;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.dto.LatestSection;
import net.java21.blog.backend.portal.dto.NewBlogResponse;
import net.java21.blog.backend.portal.dto.PopularTagResponse;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.dto.PortalHomeResponse;
import net.java21.blog.backend.portal.repository.PortalCardQueryRepository;
import net.java21.blog.backend.portal.repository.PortalCardRow;
import net.java21.blog.backend.portal.repository.PortalSectionQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포털 메인(003 FR-034·080·085~088·090, research P5·P6). 결과는 {@link PortalCache}에 키 {@code HOME}·{@code LATEST:{cursor}}·
 * {@code POPULARITY}로 담는다(기본 5분). 메인 영역(인기·최신)은 같은 블로그 2편까지, 추천에는 제한이 없다.
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

    public PortalService(PortalCache cache, PortalCriteriaFactory criteriaFactory, PortalCardQueryRepository cards,
            PortalSectionQueryRepository sections, PopularityCalculator popularityCalculator,
            PortalProperties properties) {
        this.cache = cache;
        this.criteriaFactory = criteriaFactory;
        this.cards = cards;
        this.sections = sections;
        this.popularityCalculator = popularityCalculator;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PortalHomeResponse home() {
        return cache.get("HOME", () -> {
            PortalCriteria criteria = criteriaFactory.now();
            Instant now = criteria.now();
            List<PortalCardResponse> curations = toCards(
                    cards.findByIds(sections.findActiveCurationPostIds(criteria, now, CURATION_LIMIT), criteria));
            List<PortalCardResponse> popular = toCards(cards.findByIds(popularIds(criteria), criteria));
            LatestSection latest = latestSection(criteria, null);
            List<PopularTagResponse> tags = sections
                    .findPopularTags(criteria, now.minus(properties.popularWindow()), POPULAR_TAG_LIMIT)
                    .stream().map(PopularTagResponse::from).toList();
            List<NewBlogResponse> newBlogs = sections
                    .findNewBlogs(criteria, now.minus(properties.topicCountWindow()), NEW_BLOG_LIMIT)
                    .stream().map(NewBlogResponse::from).toList();
            return new PortalHomeResponse(curations, popular, latest, tags, newBlogs, now);
        });
    }

    /** 커서 다음의 최신 글 묶음. 커서 형식이 틀리면 400 {@code VALIDATION_FAILED}(field {@code cursor}). */
    @Transactional(readOnly = true)
    public LatestSection latest(String cursor) {
        PortalCursor.Position after = PortalCursor.decode(cursor);
        String key = after == null ? "LATEST:" : "LATEST:" + cursor;
        return cache.get(key, () -> latestSection(criteriaFactory.now(), after));
    }

    /** 인기 점수 스냅숏(캐시 항목 {@code POPULARITY}). 주제 페이지 인기순도 이것을 쓴다. */
    @Transactional(readOnly = true)
    public PopularitySnapshot popularity() {
        return cache.get("POPULARITY", () -> popularityCalculator.calculate(criteriaFactory.now()));
    }

    private List<Long> popularIds(PortalCriteria criteria) {
        PopularitySnapshot snapshot = cache.get("POPULARITY", () -> popularityCalculator.calculate(criteria));
        return PerBlogCap.apply(snapshot.entries(), PopularitySnapshot.Entry::blogId, PerBlogCap.PER_BLOG,
                POPULAR_LIMIT).items().stream().map(PopularitySnapshot.Entry::postId).toList();
    }

    private LatestSection latestSection(PortalCriteria criteria, PortalCursor.Position after) {
        List<PortalCardRow> rows = cards.findLatest(criteria, after, LATEST_WINDOW + 1);
        PerBlogCap.Result<PortalCardRow> capped = PerBlogCap.apply(
                rows.subList(0, Math.min(rows.size(), LATEST_WINDOW)), PortalCardRow::blogId, PerBlogCap.PER_BLOG,
                LATEST_LIMIT);
        boolean more = capped.examined() < rows.size();
        String next = more && capped.lastExamined() != null
                ? PortalCursor.encode(new PortalCursor.Position(capped.lastExamined().publishedAt(),
                        capped.lastExamined().id()))
                : null;
        return new LatestSection(toCards(capped.items()), next);
    }

    static List<PortalCardResponse> toCards(List<PortalCardRow> rows) {
        return rows.stream().map(PortalCardResponse::from).toList();
    }
}
