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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.external.classify.KeywordDictionary;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.ClassificationStatsResponse;
import net.java21.blog.backend.external.repository.ClassificationStatsQueryRepository;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 분류 현황(007 FR-122, SC-019, research E11). 최근 30일 창으로 계산하고 5분 동안 같은 결과를 준다(Caffeine, {@code generatedAt}).
 * 쿼리는 계산 한 번에 4회.
 */
@Service
public class ClassificationStatsService {

    public static final Duration WINDOW = Duration.ofDays(30);
    public static final Duration CACHE_TTL = Duration.ofMinutes(5);

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
