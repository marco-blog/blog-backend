package net.java21.blog.backend.external.classify;

import static net.java21.blog.backend.external.classify.ClassifyTestSupport.major;
import static net.java21.blog.backend.external.classify.ClassifyTestSupport.minor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.external.domain.TopicMappingRule;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.fetch.TopicAssigner;
import net.java21.blog.backend.external.repository.TopicMappingRuleRepository;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.domain.TopicNames;
import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 007 T046: 주제 결정 순서 RULE → AUTO(기준 이상, 기준은 운영 설정) → DEFAULT. RULE이면 분류기 호출 없음·검수 없음, DEFAULT이고 기준
 * 미만(일치 없음 포함)이면 검수 대기, 분류 결과는 채택과 관계없이 기록, 숨긴 주제는 채택하지 않음, 묶음 하나에서 규칙·주제를 한 번만 읽음
 * (FR-118, FR-119, research E10).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicDeciderTest {

    private static final long DEFAULT_TOPIC = 99L;
    private static final TopicNames NAMES = new TopicNames("ko", "en", "ja", "zh");

    @Mock
    private TopicMappingRuleRepository ruleRepository;
    @Mock
    private TopicQueryRepository topicQueryRepository;
    @Mock
    private TopicClassifier classifier;
    @Mock
    private SystemSettingsService settings;

    private TopicDecider decider;

    @BeforeEach
    void setUp() {
        decider = new TopicDecider(ruleRepository, topicQueryRepository, classifier, settings);
        Topic root = TestEntities.with(new Topic(null, "knowledge", NAMES, 0, null, false), "id", 1L);
        Topic science = TestEntities.with(new Topic(root, "science", NAMES, 0, null, false), "id", 12L);
        when(ruleRepository.findAllForMatching()).thenReturn(List.of(
                TestEntities.with(new TopicMappingRule("physics", science, 0, null), "id", 1L)));
        when(topicQueryRepository.findVisible()).thenReturn(List.of(major(1, "knowledge"), minor(11, 1, "it"),
                minor(12, 1, "science")));
        when(settings.autoClassifyMinConfidence()).thenReturn(0.7);
    }

    private static FeedItem item(String... terms) {
        return new FeedItem("g", "https://x.example/1", "title", "summary", null, Instant.EPOCH, List.of(terms));
    }

    @Test
    void ruleWinsWithoutClassifierOrReview() {
        TopicAssigner.Decision decision = decider.start().decide(item("Physics"), DEFAULT_TOPIC);

        assertThat(decision).isEqualTo(new TopicAssigner.Decision(12L, TopicSource.RULE, null, null, null, false));
        verifyNoInteractions(classifier);
    }

    @Test
    void confidentClassificationIsAuto() {
        when(classifier.classify(any())).thenReturn(new ClassificationResult(11L, 0.7, "keyword-v1"));

        TopicAssigner.Decision decision = decider.start().decide(item("misc"), DEFAULT_TOPIC);

        assertThat(decision).isEqualTo(new TopicAssigner.Decision(11L, TopicSource.AUTO, 11L, 0.7, "keyword-v1",
                false));
    }

    @Test
    void lowConfidenceFallsBackToDefaultWithReviewAndRecordsPrediction() {
        when(classifier.classify(any())).thenReturn(new ClassificationResult(11L, 0.69, "keyword-v1"));
        assertThat(decider.start().decide(item(), DEFAULT_TOPIC)).isEqualTo(
                new TopicAssigner.Decision(DEFAULT_TOPIC, TopicSource.DEFAULT, 11L, 0.69, "keyword-v1", true));

        when(classifier.classify(any())).thenReturn(ClassificationResult.none("keyword-v1"));
        assertThat(decider.start().decide(item(), DEFAULT_TOPIC)).isEqualTo(
                new TopicAssigner.Decision(DEFAULT_TOPIC, TopicSource.DEFAULT, null, 0.0, "keyword-v1", true));
    }

    @Test
    void thresholdComesFromSettings() {
        when(settings.autoClassifyMinConfidence()).thenReturn(0.5);
        when(classifier.classify(any())).thenReturn(new ClassificationResult(11L, 0.5, "keyword-v1"));

        assertThat(decider.start().decide(item(), DEFAULT_TOPIC).source()).isEqualTo(TopicSource.AUTO);
    }

    @Test
    void confidentPredictionOfHiddenTopicIsNotAdopted() {
        when(classifier.classify(any())).thenReturn(new ClassificationResult(55L, 0.9, "keyword-v1"));

        TopicAssigner.Decision decision = decider.start().decide(item(), DEFAULT_TOPIC);

        assertThat(decision).isEqualTo(new TopicAssigner.Decision(DEFAULT_TOPIC, TopicSource.DEFAULT, 55L, 0.9,
                "keyword-v1", false));
    }

    @Test
    void batchLoadsRulesAndTopicsOnceAndNothingWithoutNewPosts() {
        TopicAssigner.Batch empty = decider.start();
        verify(ruleRepository, never()).findAllForMatching();

        when(classifier.classify(any())).thenReturn(ClassificationResult.none("keyword-v1"));
        TopicAssigner.Batch batch = decider.start();
        batch.decide(item(), DEFAULT_TOPIC);
        batch.decide(item("physics"), DEFAULT_TOPIC);
        batch.decide(item(), DEFAULT_TOPIC);
        verify(ruleRepository, times(1)).findAllForMatching();
        verify(topicQueryRepository, times(1)).findVisible();
        assertThat(empty).isNotNull();
    }
}
