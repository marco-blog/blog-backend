package net.java21.blog.backend.external.classify;

import static net.java21.blog.backend.external.classify.ClassifyTestSupport.rows;
import static net.java21.blog.backend.external.classify.ClassifyTestSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 007 T046: 키워드 사전 분류 — 위치 가중치 3·2·1, 같은 낱말은 위치마다 한 번, 신뢰도 {@code (top/sum) × min(1, top/4)} 경계,
 * 한국어·일본어·중국어 부분 문자열과 영어 토큰, 일치 없음은 null·0, 버전 (FR-118, research E9).
 */
class KeywordTopicClassifierTest {

    private static final String DICT = """
            version: keyword-v1
            topics:
              it-internet: [spring, java, 개발, プログラミング, 编程, node.js]
              pets: [고양이, cat, 宠物]
              music: [concert, 음악]
            """;

    private KeywordTopicClassifier classifier;

    @BeforeEach
    void setUp() {
        TopicQueryRepository topics = mock(TopicQueryRepository.class);
        when(topics.findAll()).thenReturn(rows("it-internet", "pets", "music"));
        classifier = new KeywordTopicClassifier(new KeywordDictionary(topics, yaml(DICT)));
    }

    private ClassificationResult classify(String title, String summary, String... terms) {
        return classifier.classify(new ClassifierInput(title, summary, List.of(terms)));
    }

    @Test
    void positionWeightsAndOneCountPerPosition() {
        ClassificationResult all = classify("Spring spring SPRING guide", "spring", "Spring");
        assertThat(all.topicId()).isEqualTo(11L);
        assertThat(all.confidence()).isCloseTo(1.0, within(1e-9)); // 3+2+1=6, 단독
        assertThat(all.version()).isEqualTo("keyword-v1");

        assertThat(classify("spring", null).confidence()).isCloseTo(0.5, within(1e-9)); // 제목 2 → min(1, 2/4)
        assertThat(classify(null, "java", "spring").confidence()).isCloseTo(1.0, within(1e-9)); // 3+1=4
        assertThat(classify("java", "spring").confidence()).isCloseTo(0.75, within(1e-9)); // 2+1=3
    }

    @Test
    void competingTopicsLowerConfidence() {
        // it 3(태그), pets 1(요약) → (3/4) × (3/4)
        ClassificationResult result = classify(null, "cat", "java");
        assertThat(result.topicId()).isEqualTo(11L);
        assertThat(result.confidence()).isCloseTo(0.5625, within(1e-9));
        // 같은 점수면 주제 id가 작은 것
        ClassificationResult tie = classify("concert cat", null);
        assertThat(tie.topicId()).isEqualTo(12L);
        assertThat(tie.confidence()).isCloseTo(0.5 * 0.5, within(1e-9));
    }

    @Test
    void cjkKeywordsMatchAsSubstringsAndEnglishAsTokens() {
        assertThat(classify("우리집 고양이들의 하루", null).topicId()).isEqualTo(12L);
        assertThat(classify("初心者のためのプログラミング入門", null).topicId()).isEqualTo(11L);
        assertThat(classify("我的宠物日记", null).topicId()).isEqualTo(12L);
        assertThat(classify("Springfield travel", null).topicId()).isNull();
        assertThat(classify("Node.js 22 released", null).topicId()).isEqualTo(11L);
        assertThat(classify("ＪＡＶＡ　入門", null).topicId()).isEqualTo(11L); // NFKC
    }

    @Test
    void noMatchIsNullWithZeroConfidence() {
        ClassificationResult none = classify("Weekend notes", "nothing here");
        assertThat(none.topicId()).isNull();
        assertThat(none.confidence()).isZero();
        assertThat(none.version()).isEqualTo("keyword-v1");
        assertThat(classify(null, null).topicId()).isNull();
        assertThat(KeywordTopicClassifier.confidence(0, 0)).isZero();
    }
}
