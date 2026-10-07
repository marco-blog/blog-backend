package net.java21.blog.backend.external.classify;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import net.java21.blog.backend.topic.repository.TopicRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 자동 분류 키워드 사전(007 research E9). 기동 때 {@code external/topic-keywords.yml}(소분류 slug → 낱말 목록)을 읽어 정규화하고, 처음
 * 분류할 때 DB의 소분류 slug와 맞춰 주제 id로 바꾼다(그때 이미 003 {@code TopicSeeder}가 주제를 넣었다). 없는 slug·대분류 slug 항목은
 * 경고 로그 후 무시한다(운영자가 주제를 바꿔도 기동·수집은 된다). 사전은 코드와 함께 PR로 바뀐다.
 */
@Component
public class KeywordDictionary {

    public static final String RESOURCE = "external/topic-keywords.yml";

    private static final Logger log = LoggerFactory.getLogger(KeywordDictionary.class);

    private final TopicQueryRepository topics;
    private final String version;
    private final Map<String, List<String>> bySlug;
    private volatile Map<Long, List<String>> byTopicId;

    @Autowired
    public KeywordDictionary(TopicQueryRepository topics) {
        this(topics, new ClassPathResource(RESOURCE));
    }

    public KeywordDictionary(TopicQueryRepository topics, Resource resource) {
        this.topics = topics;
        Parsed parsed = parse(resource);
        this.version = parsed.version();
        this.bySlug = parsed.bySlug();
    }

    /** 분류 방식 버전(사전 파일의 {@code version}). */
    public String version() {
        return version;
    }

    /** slug → 정규화한 낱말(파일 순서, 한 주제 안의 중복은 하나로). */
    public Map<String, List<String>> bySlug() {
        return bySlug;
    }

    /** 주제(소분류) id → 정규화한 낱말. 처음 부를 때 한 번 DB 주제와 맞춘다. */
    public Map<Long, List<String>> byTopicId() {
        Map<Long, List<String>> resolved = byTopicId;
        if (resolved == null) {
            synchronized (this) {
                resolved = byTopicId;
                if (resolved == null) {
                    resolved = resolve(topics.findAll());
                    byTopicId = resolved;
                }
            }
        }
        return resolved;
    }

    Map<Long, List<String>> resolve(List<TopicRow> rows) {
        Map<String, Long> minors = new LinkedHashMap<>();
        for (TopicRow row : rows) {
            if (!row.isMajor()) {
                minors.put(row.slug(), row.id());
            }
        }
        Map<Long, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : bySlug.entrySet()) {
            Long id = minors.get(entry.getKey());
            if (id == null) {
                log.warn("Keyword dictionary topic not found (ignored): {}", entry.getKey());
                continue;
            }
            out.put(id, entry.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    private record Parsed(String version, Map<String, List<String>> bySlug) {
    }

    @SuppressWarnings("unchecked")
    private static Parsed parse(Resource resource) {
        Object root;
        try (InputStream in = resource.getInputStream()) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read keyword dictionary " + resource + ": " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("version") instanceof String version)
                || version.isBlank() || !(map.get("topics") instanceof Map<?, ?> topicMap)) {
            throw new IllegalStateException("Keyword dictionary needs version and topics: " + resource);
        }
        Map<String, List<String>> bySlug = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<Object, Object>) topicMap).entrySet()) {
            if (!(entry.getValue() instanceof List<?> words)) {
                throw new IllegalStateException("Keyword dictionary topic needs a list: " + entry.getKey());
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (Object word : words) {
                String value = KeywordNormalizer.normalize(String.valueOf(word));
                if (!value.isEmpty()) {
                    normalized.add(value);
                }
            }
            bySlug.put(String.valueOf(entry.getKey()), List.copyOf(new ArrayList<>(normalized)));
        }
        return new Parsed(version.strip(), Collections.unmodifiableMap(bySlug));
    }
}
