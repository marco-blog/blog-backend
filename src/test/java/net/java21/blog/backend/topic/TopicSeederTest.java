package net.java21.blog.backend.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.domain.TopicNames;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.support.JpaRepositoryTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;

/**
 * 초기 주제 목록(T008, FR-075, research P2): 처음 실행에 대분류 5·소분류 39와 초기 고정 3개, 다시 실행하면 0건, 운영자가 바꾼 값은
 * 덮어쓰지 않음, 잘못된 seed는 기동 실패.
 */
@JpaRepositoryTest
class TopicSeederTest {

    @Autowired
    private TopicRepository topicRepository;
    @Autowired
    private EntityManager em;

    @Test
    void seedsFiveMajorsAndThirtyNineMinorsOnceWithInitialPins() {
        TopicSeeder seeder = new TopicSeeder(topicRepository);

        TopicSeeder.Result first = seeder.seed();
        em.flush();
        em.clear();

        assertThat(first).isEqualTo(new TopicSeeder.Result(5, 39));
        List<Topic> all = topicRepository.findAll();
        assertThat(all).hasSize(44);
        assertThat(all.stream().filter(Topic::isPinnedOnTab).map(Topic::getSlug))
                .containsExactlyInAnyOrder("it-internet", "mobile", "it-product-review");
        Map<String, Topic> bySlug = all.stream().collect(Collectors.toMap(Topic::getSlug, t -> t));
        assertThat(bySlug.get("knowledge").getCardColor()).isEqualTo("#3D7DD8");
        assertThat(bySlug.get("knowledge").getSortOrder()).isEqualTo(4);
        assertThat(bySlug.get("it-internet").getParent().getSlug()).isEqualTo("knowledge");
        assertThat(bySlug.get("it-internet").getCardColor()).isNull();
        assertThat(bySlug.get("it-internet").getNames().ko()).isEqualTo("IT 인터넷");
        assertThat(all).allSatisfy(t -> assertThat(t.getNames().hasBlank()).isFalse());

        assertThat(seeder.seed()).isEqualTo(new TopicSeeder.Result(0, 0));
        assertThat(topicRepository.count()).isEqualTo(44);
    }

    @Test
    void doesNotOverwriteOperatorChanges() {
        TopicSeeder seeder = new TopicSeeder(topicRepository);
        seeder.seed();
        Topic golf = topicRepository.findAll().stream().filter(t -> t.getSlug().equals("golf")).findFirst().orElseThrow();
        golf.rename(new TopicNames("골프!", "Golf!", "ゴルフ!", "高尔夫!"));
        golf.moveTo(99);
        golf.hide();
        Topic itInternet = topicRepository.findAll().stream().filter(t -> t.getSlug().equals("it-internet"))
                .findFirst().orElseThrow();
        itInternet.unpin();
        em.flush();
        em.clear();

        assertThat(seeder.seed()).isEqualTo(new TopicSeeder.Result(0, 0));
        em.flush();
        em.clear();

        Topic found = topicRepository.findById(golf.getId()).orElseThrow();
        assertThat(found.getNames().ko()).isEqualTo("골프!");
        assertThat(found.getSortOrder()).isEqualTo(99);
        assertThat(found.isAdminHidden()).isTrue();
        assertThat(topicRepository.findById(itInternet.getId()).orElseThrow().isPinnedOnTab()).isFalse();
    }

    @Test
    void addsOnlyMissingTopicsUnderExistingMajor() {
        Topic sports = new Topic(null, "sports", new TopicNames("스포츠", "Sports", "スポーツ", "体育"), 3, "#E2574C", false);
        topicRepository.save(sports);

        TopicSeeder.Result result = new TopicSeeder(topicRepository).seed();

        assertThat(result).isEqualTo(new TopicSeeder.Result(4, 39));
        assertThat(topicRepository.findAll().stream().filter(t -> t.getSlug().equals("golf")).findFirst()
                .orElseThrow().getParent().getId()).isEqualTo(sports.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"slug\":\"life\",\"names\":{\"ko\":\"라이프\",\"en\":\"Life\",\"ja\":\"\",\"zh-CN\":\"生活\"}}]",
            "[{\"slug\":\"life\",\"names\":{\"ko\":\"라이프\",\"en\":\"Life\",\"ja\":\"ライフ\"}}]",
            "[{\"slug\":\"life\"}]",
            "[{\"slug\":\"Life\",\"names\":{\"ko\":\"a\",\"en\":\"b\",\"ja\":\"c\",\"zh-CN\":\"d\"}}]",
            "[{\"slug\":\"l\",\"names\":{\"ko\":\"a\",\"en\":\"b\",\"ja\":\"c\",\"zh-CN\":\"d\"}}]",
            "[{\"slug\":\"life\",\"names\":{\"ko\":\"a\",\"en\":\"b\",\"ja\":\"c\",\"zh-CN\":\"d\"},"
                    + "\"children\":[{\"slug\":\"life\",\"names\":{\"ko\":\"a\",\"en\":\"b\",\"ja\":\"c\",\"zh-CN\":\"d\"}}]}]",
            "[{\"slug\":\"life\",\"cardColor\":\"red\",\"names\":{\"ko\":\"a\",\"en\":\"b\",\"ja\":\"c\",\"zh-CN\":\"d\"}}]",
            "[{\"slug\":\"life\",\"names\":{\"ko\":\"a\",\"en\":\"b\",\"ja\":\"c\",\"zh-CN\":\"d\"},\"children\":[{\"slug\":"
                    + "\"pets\",\"names\":{\"ko\":\"a\",\"en\":\"b\",\"ja\":\"c\",\"zh-CN\":\"d\"},\"children\":[{\"slug\":"
                    + "\"cats\",\"names\":{\"ko\":\"a\",\"en\":\"b\",\"ja\":\"c\",\"zh-CN\":\"d\"}}]}]}]",
            "not json"
    })
    void invalidSeedStopsStartup(String json) {
        TopicSeeder seeder = new TopicSeeder(topicRepository,
                new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(seeder::seed).isInstanceOf(IllegalStateException.class);
        assertThat(topicRepository.count()).isZero();
    }

    @Test
    void runSeedsLikeSeed() throws Exception {
        new TopicSeeder(topicRepository).run(null);

        assertThat(topicRepository.count()).isEqualTo(44);
    }
}
