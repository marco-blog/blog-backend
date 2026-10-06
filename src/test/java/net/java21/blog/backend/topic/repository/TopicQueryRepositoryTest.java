package net.java21.blog.backend.topic.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.topic.domain.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * 주제 조회(T009, research P2): 전체 트리를 대분류·소분류 순서로 쿼리 1회, 공개 트리는 운영자 숨김 주제와 숨긴 대분류의 소분류를
 * 빼고, slug로 찾기와 대분류의 소분류 id 목록(숨김 제외).
 */
@JpaRepositoryTest
@Import(TopicQueryRepository.class)
class TopicQueryRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private TopicQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private Topic life;
    private Topic sports;
    private Topic pets;
    private Topic cooking;
    private Topic golf;
    private Topic soccer;
    private Topic knowledge;
    private Topic mobile;

    @BeforeEach
    void setUp() {
        JpaFixtures fx = new JpaFixtures(em);
        sports = fx.topic(null, "sports", 2);
        life = fx.topic(null, "life", 1);
        knowledge = fx.topic(null, "knowledge", 3);
        golf = fx.topic(sports, "golf", 2);
        soccer = fx.topic(sports, "soccer", 1);
        cooking = fx.topic(life, "cooking", 2);
        pets = fx.topic(life, "pets", 1);
        mobile = fx.topic(knowledge, "mobile", 0);
        Topic hiddenMinor = fx.topic(life, "hidden-minor", 3);
        hiddenMinor.hide();
        knowledge.hide();
        fx.flushAndClear();
    }

    @Test
    void findAllOrdersMajorsThenTheirMinorsInOneQuery() {
        queryCounter.reset();
        List<TopicRow> rows = repository.findAll();

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(rows).extracting(TopicRow::slug).containsExactly("life", "pets", "cooking", "hidden-minor",
                "sports", "soccer", "golf", "knowledge", "mobile");
        TopicRow mobileRow = rows.get(8);
        assertThat(mobileRow.parentId()).isEqualTo(knowledge.getId());
        assertThat(mobileRow.adminHidden()).isFalse();
        assertThat(mobileRow.parentHidden()).isTrue();
        assertThat(mobileRow.effectiveHidden()).isTrue();
        assertThat(mobileRow.names().ko()).isEqualTo("mobile-ko");
        assertThat(rows.get(0).isMajor()).isTrue();
        assertThat(rows.get(0).cardColor()).isEqualTo("#3D7DD8");
        assertThat(rows.get(0).createdAt()).isNotNull();
    }

    @Test
    void visibleTreeSkipsHiddenTopicsAndChildrenOfHiddenMajors() {
        queryCounter.reset();
        List<TopicRow> rows = repository.findVisible();

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(rows).extracting(TopicRow::slug).containsExactly("life", "pets", "cooking", "sports", "soccer",
                "golf");
    }

    @Test
    void findBySlugAndVisibleChildren() {
        assertThat(repository.findBySlug("golf")).get().extracting(TopicRow::id).isEqualTo(golf.getId());
        assertThat(repository.findBySlug("nope")).isEmpty();

        queryCounter.reset();
        assertThat(repository.findVisibleChildIds(life.getId())).containsExactly(pets.getId(), cooking.getId());
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.findVisibleChildIds(sports.getId())).containsExactly(soccer.getId(), golf.getId());
        assertThat(repository.findVisibleChildIds(knowledge.getId())).isEmpty();
        assertThat(mobile.getId()).isNotNull();
    }
}
