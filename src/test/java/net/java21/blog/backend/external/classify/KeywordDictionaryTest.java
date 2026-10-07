package net.java21.blog.backend.external.classify;

import static net.java21.blog.backend.external.classify.ClassifyTestSupport.rows;
import static net.java21.blog.backend.external.classify.ClassifyTestSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import net.java21.blog.backend.topic.TopicSeeder;
import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 007 T046: 키워드 사전 — 사전의 모든 slug가 003 {@code TopicSeeder} 소분류에 있음, 소분류마다 10개 이상, 정규화 후 주제 사이 중복 없음,
 * 없는 slug 항목은 경고 후 무시, 형식 오류는 기동 실패 (research E9).
 */
class KeywordDictionaryTest {

    private static List<TopicSeeder.SeedTopic> seed() throws Exception {
        try (InputStream in = new ClassPathResource("portal/topics-seed.json").getInputStream()) {
            return JsonMapper.builder().build().readValue(in, new TypeReference<List<TopicSeeder.SeedTopic>>() { });
        }
    }

    @Test
    void shippedDictionaryCoversEverySeedMinorWithTenDistinctKeywords() throws Exception {
        Set<String> minors = seed().stream().flatMap(m -> m.children().stream()).map(TopicSeeder.SeedTopic::slug)
                .collect(Collectors.toSet());
        KeywordDictionary dictionary = new KeywordDictionary(mock(TopicQueryRepository.class));

        assertThat(dictionary.version()).isEqualTo("keyword-v1");
        assertThat(minors).containsAll(dictionary.bySlug().keySet());
        assertThat(dictionary.bySlug().keySet()).containsExactlyInAnyOrderElementsOf(minors);
        Map<String, String> owner = new HashMap<>();
        List<String> duplicates = new ArrayList<>();
        dictionary.bySlug().forEach((slug, words) -> {
            assertThat(words).as(slug).hasSizeGreaterThanOrEqualTo(10);
            for (String word : words) {
                assertThat(word).isEqualTo(KeywordNormalizer.normalize(word));
                String previous = owner.putIfAbsent(word, slug);
                if (previous != null) {
                    duplicates.add(word + " (" + previous + ", " + slug + ")");
                }
            }
        });
        assertThat(duplicates).isEmpty();
    }

    @Test
    void unknownSlugsAreIgnoredAndResolutionHappensOnce() {
        TopicQueryRepository topics = mock(TopicQueryRepository.class);
        when(topics.findAll()).thenReturn(rows("pets"));
        KeywordDictionary dictionary = new KeywordDictionary(topics, yaml("""
                version: v-test
                topics:
                  pets: [Cat, "  cat ", Dog]
                  gone: [ghost]
                  root: [major]
                """));

        assertThat(dictionary.byTopicId()).containsExactly(Map.entry(11L, List.of("cat", "dog")));
        dictionary.byTopicId();
        verify(topics, times(1)).findAll();
    }

    @Test
    void malformedDictionaryFailsFast() {
        TopicQueryRepository topics = mock(TopicQueryRepository.class);
        assertThatThrownBy(() -> new KeywordDictionary(topics, yaml("topics: {}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeywordDictionary(topics, yaml("version: v\ntopics:\n  pets: cat")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeywordDictionary(topics, yaml("version: v\ntopics: [")))
                .isInstanceOf(IllegalStateException.class);
    }
}
