package net.java21.blog.backend.admin.external;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.external.classify.KeywordDictionary;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.ClassificationStatsResponse;
import net.java21.blog.backend.external.repository.ClassificationStatsQueryRepository;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 분류 현황(007 FR-122, SC-019, research E11). 최근 30일 창으로 계산하고 5분 동안 같은 결과를 준다(Caffeine, {@code generatedAt}).
 * 쿼리는 계산 한 번에 4회. 사람이 주제를 정하면(검수 확정·주인 변경) 커밋 뒤 캐시를 비워 다음 조회가 바로 반영한다.
 */
@Service
public class ClassificationStatsService {

    public static final Duration WINDOW = Duration.ofDays(30);
    public static final Duration CACHE_TTL = Duration.ofMinutes(5);
    static final Set<String> HUMAN_DECISIONS = Set.of("external:review-confirm",
            "external:owner-topic");

    private final ClassificationStatsQueryRepository repository;
    private final SystemSettingsService settings;
    private final KeywordDictionary dictionary;
    private final Clock clock;
    private final Cache<String, ClassificationStatsResponse> cache;

    @Autowired
    public ClassificationStatsService(ClassificationStatsQueryRepository repository, SystemSettingsService settings,
            KeywordDictionary dictionary, Clock clock) {
        this(repository, settings, dictionary, clock, Ticker.systemTicker());
    }

    /** 테스트에서 캐시 시간을 정할 때. */
    public ClassificationStatsService(ClassificationStatsQueryRepository repository, SystemSettingsService settings,
            KeywordDictionary dictionary, Clock clock, Ticker ticker) {
        this.repository = repository;
        this.settings = settings;
        this.dictionary = dictionary;
        this.clock = clock;
        this.cache = Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).ticker(ticker).maximumSize(1).build();
    }

    public ClassificationStatsResponse stats() {
        return cache.get("stats", key -> compute());
    }

    /** 검수 확정·주인 주제 변경이 커밋되면 캐시를 비운다(수집·링크 점검 같은 다른 포털 변경은 5분 캐시를 그대로 쓴다). */
    @TransactionalEventListener(fallbackExecution = true)
    public void onPortalChanged(PortalChangedEvent event) {
        if (HUMAN_DECISIONS.contains(event.reason())) {
            cache.invalidateAll();
        }
    }

    ClassificationStatsResponse compute() {
        Instant now = clock.instant();
        Instant from = now.minus(WINDOW);
        ClassificationStatsQueryRepository.Ratio classifier = repository.classifierAccuracy(from);
        ClassificationStatsQueryRepository.Ratio decided = repository.finalAccuracy(from);
        Map<Long, Map<TopicSource, Long>> byTopic = new LinkedHashMap<>();
        for (ClassificationStatsQueryRepository.SourceCount row : repository.distribution(from)) {
            byTopic.computeIfAbsent(row.topicId(), id -> zeros()).merge(row.source(), row.count(), Long::sum);
        }
        List<ClassificationStatsResponse.TopicCount> distribution = new ArrayList<>();
        byTopic.forEach((topicId, counts) -> distribution.add(new ClassificationStatsResponse.TopicCount(topicId,
                counts.values().stream().mapToLong(Long::longValue).sum(), counts)));
        distribution.sort(Comparator.comparingLong(ClassificationStatsResponse.TopicCount::total).reversed()
                .thenComparingLong(ClassificationStatsResponse.TopicCount::topicId));
        return new ClassificationStatsResponse(new ClassificationStatsResponse.Window(from, now),
                new ClassificationStatsResponse.Accuracy(classifier.sample(), classifier.hit(),
                        ClassificationStatsResponse.rate(classifier.hit(), classifier.sample())),
                new ClassificationStatsResponse.FinalAccuracy(decided.sample(), decided.hit(),
                        ClassificationStatsResponse.rate(decided.hit(), decided.sample())),
                List.copyOf(distribution), repository.pendingReviews(), settings.autoClassifyMinConfidence(),
                dictionary.version(), now);
    }

    private static Map<TopicSource, Long> zeros() {
        Map<TopicSource, Long> map = new EnumMap<>(TopicSource.class);
        for (TopicSource source : TopicSource.values()) {
            map.put(source, 0L);
        }
        return map;
    }
}
