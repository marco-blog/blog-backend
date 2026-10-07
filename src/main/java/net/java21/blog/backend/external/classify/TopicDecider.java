package net.java21.blog.backend.external.classify;

import java.util.HashSet;
import java.util.Set;

import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.fetch.TopicAssigner;
import net.java21.blog.backend.external.repository.TopicMappingRuleRepository;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import net.java21.blog.backend.topic.repository.TopicRow;
import org.springframework.stereotype.Component;

/**
 * 새로 수집한 글의 주제(007 FR-118·119, research E10). 순서: 매핑 규칙(RULE) → 자동 분류 신뢰도가 운영 설정
 * {@code external.auto-classify-min-confidence} 이상이면 그 주제(AUTO) → 블로그 기본 주제(DEFAULT).
 * <ul>
 *   <li>RULE이면 분류기를 돌리지 않고 검수도 만들지 않는다(운영자가 정한 규칙이 이미 사람의 판단).</li>
 *   <li>분류 결과는 채택 여부와 관계없이 {@code classifier_*}에 남긴다(정확도 측정, FR-122).</li>
 *   <li>DEFAULT이고 신뢰도가 기준 미만이면(일치 없음 포함) 검수 대기(PENDING)를 만든다.</li>
 *   <li>예측 주제가 운영자 숨김이면 채택하지 않는다(DEFAULT).</li>
 * </ul>
 * 규칙·보이는 주제·기준값은 새 글이 처음 나올 때 피드 한 번에 한 번만 읽는다(새 글이 없으면 쿼리 없음).
 */
@Component
public class TopicDecider implements TopicAssigner {

    private final TopicMappingRuleRepository ruleRepository;
    private final TopicQueryRepository topicQueryRepository;
    private final TopicClassifier classifier;
    private final SystemSettingsService settings;

    public TopicDecider(TopicMappingRuleRepository ruleRepository, TopicQueryRepository topicQueryRepository,
            TopicClassifier classifier, SystemSettingsService settings) {
        this.ruleRepository = ruleRepository;
        this.topicQueryRepository = topicQueryRepository;
        this.classifier = classifier;
        this.settings = settings;
    }

    @Override
    public Batch start() {
        return new LazyBatch();
    }

    private final class LazyBatch implements Batch {

        private MappingRuleMatcher matcher;
        private Set<Long> visibleMinors;
        private double minConfidence;

        private void load() {
            if (matcher != null) {
                return;
            }
            matcher = new MappingRuleMatcher(ruleRepository.findAllForMatching());
            visibleMinors = new HashSet<>();
            for (TopicRow row : topicQueryRepository.findVisible()) {
                if (!row.isMajor()) {
                    visibleMinors.add(row.id());
                }
            }
            minConfidence = settings.autoClassifyMinConfidence();
        }

        @Override
        public Decision decide(FeedItem item, long defaultTopicId) {
            load();
            Long ruleTopic = matcher.match(item.categories());
            if (ruleTopic != null) {
                return new Decision(ruleTopic, TopicSource.RULE, null, null, null, false);
            }
            ClassificationResult result = classifier.classify(
                    new ClassifierInput(item.title(), item.summary(), item.categories()));
            boolean confident = result.topicId() != null && result.confidence() >= minConfidence;
            if (confident && visibleMinors.contains(result.topicId())) {
                return new Decision(result.topicId(), TopicSource.AUTO, result.topicId(), result.confidence(),
                        result.version(), false);
            }
            return new Decision(defaultTopicId, TopicSource.DEFAULT, result.topicId(), result.confidence(),
                    result.version(), !confident);
        }
    }
}
