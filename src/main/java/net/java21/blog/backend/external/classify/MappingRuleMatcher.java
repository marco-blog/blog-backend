package net.java21.blog.backend.external.classify;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.java21.blog.backend.external.domain.TopicMappingRule;
import net.java21.blog.backend.topic.domain.Topic;

/**
 * 매핑 규칙(007 FR-121, research E10): 피드 카테고리·태그를 정규화해 규칙 키워드와 완전 일치하는 규칙 중 우선순위가 큰 것(같으면 id가
 * 작은 것)의 주제. 규칙이 가리키는 주제가 대분류이거나 운영자 숨김(부모 포함)이면 그 규칙은 건너뛴다. 피드 한 번 수집마다 규칙 전체를
 * 한 번 읽어 만든다.
 */
public final class MappingRuleMatcher {

    private record Rule(String keyword, Long topicId) {
    }

    private final List<Rule> rules;

    public MappingRuleMatcher(List<TopicMappingRule> rules) {
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt(TopicMappingRule::getPriority).reversed()
                        .thenComparing(TopicMappingRule::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .filter(rule -> usable(rule.getTopic()))
                .map(rule -> new Rule(KeywordNormalizer.normalize(rule.getKeyword()), rule.getTopic().getId()))
                .toList();
    }

    private static boolean usable(Topic topic) {
        return topic != null && !topic.isMajor() && !topic.isEffectivelyHidden();
    }

    /** @return 일치한 규칙의 주제 id, 없으면 null */
    public Long match(List<String> terms) {
        if (rules.isEmpty() || terms == null || terms.isEmpty()) {
            return null;
        }
        Set<String> normalized = new HashSet<>();
        for (String term : terms) {
            normalized.add(KeywordNormalizer.normalize(term));
        }
        for (Rule rule : rules) {
            if (normalized.contains(rule.keyword())) {
                return rule.topicId();
            }
        }
        return null;
    }
}
