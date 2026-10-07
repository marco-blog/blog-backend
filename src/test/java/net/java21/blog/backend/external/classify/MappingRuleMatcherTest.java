package net.java21.blog.backend.external.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import net.java21.blog.backend.external.domain.TopicMappingRule;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.domain.TopicNames;
import org.junit.jupiter.api.Test;

/**
 * 007 T046: 매핑 규칙 — NFKC·trim·소문자 완전 일치, 우선순위 큰 것·같으면 id 작은 것, 숨김·대분류 대상 규칙 건너뜀 (FR-121, research E10).
 */
class MappingRuleMatcherTest {

    private static final TopicNames NAMES = new TopicNames("ko", "en", "ja", "zh");

    private final Topic major = TestEntities.with(new Topic(null, "knowledge", NAMES, 0, null, false), "id", 1L);
    private final Topic it = TestEntities.with(new Topic(major, "it-internet", NAMES, 0, null, false), "id", 11L);
    private final Topic science = TestEntities.with(new Topic(major, "science", NAMES, 1, null, false), "id", 12L);

    private static TopicMappingRule rule(long id, String keyword, Topic topic, int priority) {
        return TestEntities.with(new TopicMappingRule(keyword, topic, priority, null), "id", id);
    }

    @Test
    void normalizedExactMatch() {
        MappingRuleMatcher matcher = new MappingRuleMatcher(List.of(rule(1, "Spring Boot", it, 0)));

        assertThat(matcher.match(List.of("  ＳＰＲＩＮＧ   boot "))).isEqualTo(11L);
        assertThat(matcher.match(List.of("spring"))).isNull();
        assertThat(matcher.match(List.of("spring boot 4"))).isNull();
        assertThat(matcher.match(List.of())).isNull();
        assertThat(matcher.match(null)).isNull();
        assertThat(new MappingRuleMatcher(List.of()).match(List.of("x"))).isNull();
    }

    @Test
    void higherPriorityThenSmallerIdWins() {
        MappingRuleMatcher matcher = new MappingRuleMatcher(List.of(
                rule(3, "java", it, 5), rule(2, "physics", science, 5), rule(1, "lab", science, 9)));

        assertThat(matcher.match(List.of("java", "lab"))).isEqualTo(12L);
        assertThat(matcher.match(List.of("java", "physics"))).isEqualTo(12L);
    }

    @Test
    void rulesPointingAtHiddenOrMajorTopicsAreSkipped() {
        Topic hidden = TestEntities.with(new Topic(major, "hidden", NAMES, 2, null, false), "id", 13L);
        hidden.hide();
        MappingRuleMatcher matcher = new MappingRuleMatcher(List.of(
                rule(1, "x", hidden, 9), rule(2, "x2", major, 9), rule(3, "y", it, 0)));

        assertThat(matcher.match(List.of("x", "x2", "y"))).isEqualTo(11L);
        assertThat(matcher.match(List.of("x"))).isNull();
    }
}
