package net.java21.blog.backend.topic;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.domain.TopicNames;
import net.java21.blog.backend.topic.repository.TopicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 초기 주제 목록(003 FR-075, research P2, 결정 표 6번). 기동 때 {@code portal/topics-seed.json}을 읽어 없는 slug만 넣는다(멱등).
 * 이미 있는 주제는 운영자가 바꾼 이름·순서·숨김·고정을 덮어쓰지 않는다. seed가 잘못되면(이름 누락, slug 형식·중복) 기동을 멈춘다.
 * 티스토리 현행 목록과의 최종 대조는 운영 작업이며, 바뀌면 seed 파일만 고친다(이미 넣은 행은 관리자 콘솔에서 고친다).
 */
@Component
public class TopicSeeder implements ApplicationRunner {

    static final String SEED = "portal/topics-seed.json";

    private static final Logger log = LoggerFactory.getLogger(TopicSeeder.class);

    private final TopicRepository topicRepository;
    private final Resource seed;

    @Autowired
    public TopicSeeder(TopicRepository topicRepository) {
        this(topicRepository, new ClassPathResource(SEED));
    }

    public TopicSeeder(TopicRepository topicRepository, Resource seed) {
        this.topicRepository = topicRepository;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed();
    }

    /** @return 새로 넣은 대분류·소분류 수 */
    @Transactional
    public Result seed() {
        List<SeedTopic> majors = read();
        validate(majors);
        Map<String, Topic> existing = new HashMap<>();
        topicRepository.findAll().forEach(t -> existing.put(t.getSlug(), t));
        int insertedMajors = 0;
        int insertedMinors = 0;
        for (int i = 0; i < majors.size(); i++) {
            SeedTopic m = majors.get(i);
            Topic major = existing.get(m.slug());
            if (major == null) {
                major = topicRepository.save(new Topic(null, m.slug(), TopicNames.of(m.names()), i, m.cardColor(),
                        m.pinnedOnTab()));
                insertedMajors++;
            }
            if (!major.isMajor()) {
                continue;
            }
            List<SeedTopic> children = m.children() == null ? List.of() : m.children();
            for (int j = 0; j < children.size(); j++) {
                SeedTopic c = children.get(j);
                if (!existing.containsKey(c.slug())) {
                    topicRepository.save(new Topic(major, c.slug(), TopicNames.of(c.names()), j, c.cardColor(),
                            c.pinnedOnTab()));
                    insertedMinors++;
                }
            }
        }
        log.info("Topic seed: inserted majors={}, minors={}", insertedMajors, insertedMinors);
        return new Result(insertedMajors, insertedMinors);
    }

    private List<SeedTopic> read() {
        try (InputStream in = seed.getInputStream()) {
            return JsonMapper.builder().build().readValue(in, new TypeReference<List<SeedTopic>>() { });
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read topic seed " + seed + ": " + e.getMessage(), e);
        }
    }

    static void validate(List<SeedTopic> majors) {
        Set<String> slugs = new HashSet<>();
        List<SeedTopic> all = new ArrayList<>();
        for (SeedTopic m : majors) {
            all.add(m);
            if (m.children() != null) {
                for (SeedTopic c : m.children()) {
                    if (c.children() != null && !c.children().isEmpty()) {
                        throw new IllegalStateException("Topic seed has more than two levels: " + c.slug());
                    }
                    all.add(c);
                }
            }
        }
        for (SeedTopic t : all) {
            String slug = t.slug();
            if (slug == null || slug.length() < 2 || slug.length() > Topic.SLUG_MAX || !Topic.SLUG.matcher(slug).matches()) {
                throw new IllegalStateException("Invalid topic slug in seed: " + slug);
            }
            if (!slugs.add(slug)) {
                throw new IllegalStateException("Duplicate topic slug in seed: " + slug);
            }
            if (t.names() == null) {
                throw new IllegalStateException("Topic seed needs names in 4 languages: " + slug);
            }
            TopicNames names = TopicNames.of(t.names());
            if (names.hasBlank() || names.asMap().values().stream().anyMatch(n -> n.length() > Topic.NAME_MAX)) {
                throw new IllegalStateException("Topic seed needs names in 4 languages (1-50 chars): " + slug);
            }
            if (t.cardColor() != null && !Topic.CARD_COLOR.matcher(t.cardColor()).matches()) {
                throw new IllegalStateException("Invalid card color in seed: " + slug);
            }
        }
    }

    /** seed 파일의 주제 하나. */
    public record SeedTopic(String slug, Map<String, String> names, String cardColor, boolean pinnedOnTab,
            List<SeedTopic> children) {
    }

    /** 이번 실행에서 넣은 수. */
    public record Result(int majors, int minors) {
    }
}
