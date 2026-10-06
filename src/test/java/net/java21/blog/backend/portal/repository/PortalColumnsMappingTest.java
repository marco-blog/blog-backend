package net.java21.blog.backend.portal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.portal.domain.PortalCuration;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDailyStat;
import net.java21.blog.backend.post.domain.PostDailyStatId;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.releasenote.domain.ReleaseNote;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContent;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteContentId;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteRevision;
import net.java21.blog.backend.releasenote.domain.ReleaseNoteStatus;
import net.java21.blog.backend.releasenote.domain.RevisionContent;
import net.java21.blog.backend.releasenote.domain.TocEntry;
import net.java21.blog.backend.setting.domain.SystemSetting;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.domain.TopicNames;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 003 매핑(T005): 주제, 포털 추천·제외, 일별 통계, 운영 설정(JSON), 릴리스 노트 3개 테이블과 001 테이블에 더한 컬럼을 저장 후 다시 읽는다.
 * 실제 MySQL 스키마와의 일치는 {@code EntitySchemaValidationTest}가 확인한다.
 */
@JpaRepositoryTest
class PortalColumnsMappingTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private JpaFixtures fx;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
    }

    @Test
    void topicTreeWithFourLanguageNames() {
        Topic major = new Topic(null, "knowledge", new TopicNames("지식·동향", "Knowledge", "知識", "知识"), 4, "#3D7DD8",
                false);
        em.persist(major);
        Topic minor = new Topic(major, "it-internet", new TopicNames("IT 인터넷", "IT & Internet", "IT・インターネット",
                "IT 互联网"), 0, null, true);
        em.persist(minor);
        minor.hide();
        fx.flushAndClear();

        Topic found = em.find(Topic.class, minor.getId());
        assertThat(found.getSlug()).isEqualTo("it-internet");
        assertThat(found.getParentId()).isEqualTo(major.getId());
        assertThat(found.isMajor()).isFalse();
        assertThat(found.namesByLanguage()).containsExactly(Map.entry("ko", "IT 인터넷"), Map.entry("en", "IT & Internet"),
                Map.entry("ja", "IT・インターネット"), Map.entry("zh-CN", "IT 互联网"));
        assertThat(found.isAdminHidden()).isTrue();
        assertThat(found.isPinnedOnTab()).isTrue();
        assertThat(found.getCardColor()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(em.find(Topic.class, major.getId()).getCardColor()).isEqualTo("#3D7DD8");
        assertThat(found.isEffectivelyHidden()).isTrue();
    }

    @Test
    void topicChangesAndTwoLevelLimit() {
        Topic major = fx.topic(null, "life", 0);
        Topic minor = fx.topic(major, "pets", 1);
        minor.rename(new TopicNames("a", "b", "c", "d"));
        minor.changeColor("#000000");
        minor.pin();
        minor.unpin();
        minor.moveTo(5);
        major.hide();
        fx.flushAndClear();

        Topic found = em.find(Topic.class, minor.getId());
        assertThat(found.getNames()).isEqualTo(new TopicNames("a", "b", "c", "d"));
        assertThat(found.getCardColor()).isEqualTo("#000000");
        assertThat(found.isPinnedOnTab()).isFalse();
        assertThat(found.getSortOrder()).isEqualTo(5);
        assertThat(found.isEffectivelyHidden()).isTrue();
        em.find(Topic.class, major.getId()).unhide();
        assertThat(found.getParent().isAdminHidden()).isFalse();
        assertThatThrownBy(() -> new Topic(found, "deep", found.getNames(), 0, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newBlogHasPortalDefaultsAndPortalSettingsRoundTrip() {
        fx.flushAndClear();
        Blog fresh = em.find(Blog.class, blog.getId());
        assertThat(fresh.isPortalEnabled()).isTrue();
        assertThat(fresh.getDefaultTopic()).isNull();
        assertThat(fresh.getDefaultTopicId()).isNull();
        assertThat(fresh.getFirstPublishedAt()).isNull();

        Topic minor = fx.topic(fx.topic(null, "travel-food", 0), "domestic-travel", 0);
        fresh.changePortalSettings(false, minor);
        fresh.markFirstPublished(NOW);
        fresh.markFirstPublished(NOW.plusSeconds(60));
        fx.flushAndClear();

        Blog found = em.find(Blog.class, blog.getId());
        assertThat(found.isPortalEnabled()).isFalse();
        assertThat(found.getDefaultTopicId()).isEqualTo(minor.getId());
        assertThat(found.getFirstPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void postTopicDraftTopicAndUserLastSeenVersion() {
        Topic minor = fx.topic(fx.topic(null, "knowledge", 0), "mobile", 0);
        Post post = fx.published(blog, "글", null, 0);
        post.assignTopic(minor);
        PostDraft draft = fx.draft(post, null, List.of());
        draft.changeTopic(minor.getId());
        jdbc.update("UPDATE users SET last_seen_release_version = '1.2.0' WHERE id = ?", owner.getId());
        fx.flushAndClear();
        jdbc.update("UPDATE users SET last_seen_release_version = '1.2.0' WHERE id = ?", owner.getId());

        Post found = em.find(Post.class, post.getId());
        assertThat(found.getTopicId()).isEqualTo(minor.getId());
        assertThat(found.getTopic().getSlug()).isEqualTo("mobile");
        assertThat(em.find(PostDraft.class, post.getId()).getTopicId()).isEqualTo(minor.getId());
        assertThat(em.find(User.class, owner.getId()).getLastSeenReleaseVersion()).isEqualTo("1.2.0");

        found.assignTopic(null);
        fx.flushAndClear();
        assertThat(em.find(Post.class, post.getId()).getTopicId()).isNull();
    }

    @Test
    void curationExclusionAndDailyStat() {
        Post post = fx.published(blog, "글", null, 0);
        PortalCuration curation = new PortalCuration(post, NOW, NOW.plusSeconds(3600), 2, owner);
        em.persist(curation);
        PortalExclusion exclusion = new PortalExclusion(post, "광고성", owner);
        em.persist(exclusion);
        em.persist(new PostDailyStat(post, LocalDate.of(2026, 10, 6), 3, 1));
        fx.flushAndClear();

        PortalCuration c = em.find(PortalCuration.class, curation.getId());
        assertThat(c.getPost().getId()).isEqualTo(post.getId());
        assertThat(c.getStartsAt()).isEqualTo(NOW);
        assertThat(c.getEndsAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(c.getSortOrder()).isEqualTo(2);
        assertThat(c.getCreatedBy().getId()).isEqualTo(owner.getId());
        assertThat(c.isActiveAt(NOW)).isTrue();
        assertThat(c.isActiveAt(NOW.plusSeconds(3600))).isFalse();
        assertThat(c.isActiveAt(NOW.minusSeconds(1))).isFalse();
        c.reschedule(NOW, NOW.plusSeconds(10), 0);
        assertThatThrownBy(() -> c.reschedule(NOW, NOW, 0)).isInstanceOf(IllegalArgumentException.class);

        PortalExclusion e = em.find(PortalExclusion.class, exclusion.getId());
        assertThat(e.getPost().getId()).isEqualTo(post.getId());
        assertThat(e.getReason()).isEqualTo("광고성");
        assertThat(e.getExcludedBy().getId()).isEqualTo(owner.getId());
        e.changeReason("스팸", owner);
        assertThat(e.getReason()).isEqualTo("스팸");

        PostDailyStat stat = em.find(PostDailyStat.class, new PostDailyStatId(post.getId(), LocalDate.of(2026, 10, 6)));
        assertThat(stat.getViews()).isEqualTo(3);
        assertThat(stat.getReadCompletes()).isEqualTo(1);
        assertThat(stat.getId().statDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    @SuppressWarnings("unchecked")
    void systemSettingStoresJsonObjectStringAndNumber() {
        em.persist(new SystemSetting("portal.score-weights", Map.of("view", 1, "like", 2.5), owner));
        em.persist(new SystemSetting("portal.new-member-delay", "PT24H", null));
        em.persist(new SystemSetting("portal.min-content-length", 300, owner));
        fx.flushAndClear();

        assertThat((Map<String, Object>) em.find(SystemSetting.class, "portal.score-weights").getValue())
                .containsEntry("view", 1).containsEntry("like", 2.5);
        assertThat(em.find(SystemSetting.class, "portal.new-member-delay").getValue()).isEqualTo("PT24H");
        SystemSetting min = em.find(SystemSetting.class, "portal.min-content-length");
        assertThat(((Number) min.getValue()).intValue()).isEqualTo(300);
        assertThat(min.getUpdatedBy().getId()).isEqualTo(owner.getId());
        min.change(500, null);
        fx.flushAndClear();
        assertThat(((Number) em.find(SystemSetting.class, "portal.min-content-length").getValue()).intValue())
                .isEqualTo(500);
    }

    @Test
    void releaseNoteContentAndRevision() {
        ReleaseNote note = new ReleaseNote("1.2.0", 1, 2, 0, LocalDate.of(2026, 10, 6), owner);
        em.persist(note);
        ReleaseNoteContent ko = new ReleaseNoteContent(note, "ko");
        ko.write("제목", "## 새 기능", "<h2 id=\"새-기능\">새 기능</h2>", "새 기능",
                List.of(new TocEntry(2, "새 기능", "새-기능")));
        em.persist(ko);
        em.persist(new ReleaseNoteRevision(note, owner, Map.of("ko", new RevisionContent("제목", "## 새 기능"))));
        note.publish(NOW, owner);
        fx.flushAndClear();

        ReleaseNote found = em.find(ReleaseNote.class, note.getId());
        assertThat(found.getVersion()).isEqualTo("1.2.0");
        assertThat(found.getVersionMajor()).isEqualTo(1);
        assertThat(found.getVersionMinor()).isEqualTo(2);
        assertThat(found.getVersionPatch()).isZero();
        assertThat(found.getReleaseDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(found.getStatus()).isEqualTo(ReleaseNoteStatus.PUBLISHED);
        assertThat(found.isPublished()).isTrue();
        assertThat(found.getCurrentRevisionNo()).isEqualTo(1);
        assertThat(found.getFirstPublishedAt()).isEqualTo(NOW);
        assertThat(found.getFirstPublishedRevisionNo()).isEqualTo(1);
        assertThat(found.getPublishedAt()).isEqualTo(NOW);
        assertThat(found.getCreatedBy().getId()).isEqualTo(owner.getId());
        assertThat(found.getUpdatedBy().getId()).isEqualTo(owner.getId());

        found.revise("1.2.0", 1, 2, 0, LocalDate.of(2026, 10, 7), owner);
        found.unpublish(owner);
        found.publish(NOW.plusSeconds(60), owner);
        assertThat(found.getCurrentRevisionNo()).isEqualTo(2);
        assertThat(found.getFirstPublishedAt()).isEqualTo(NOW);
        assertThat(found.getFirstPublishedRevisionNo()).isEqualTo(1);
        found.unpublish(owner);
        assertThat(found.getPublishedAt()).isNull();

        ReleaseNoteContent content = em.find(ReleaseNoteContent.class, new ReleaseNoteContentId(note.getId(), "ko"));
        assertThat(content.getLang()).isEqualTo("ko");
        assertThat(content.getReleaseNote().getId()).isEqualTo(note.getId());
        assertThat(content.getTitle()).isEqualTo("제목");
        assertThat(content.getContentMarkdown()).isEqualTo("## 새 기능");
        assertThat(content.getContentHtml()).contains("id=\"새-기능\"");
        assertThat(content.getContentText()).isEqualTo("새 기능");
        assertThat(content.getToc()).containsExactly(new TocEntry(2, "새 기능", "새-기능"));

        ReleaseNoteRevision revision = em.createQuery("select r from ReleaseNoteRevision r", ReleaseNoteRevision.class)
                .getSingleResult();
        assertThat(revision.getRevisionNo()).isEqualTo(1);
        assertThat(revision.getStatus()).isEqualTo(ReleaseNoteStatus.DRAFT);
        assertThat(revision.getVersion()).isEqualTo("1.2.0");
        assertThat(revision.getReleaseDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(revision.getContents()).containsEntry("ko", new RevisionContent("제목", "## 새 기능"));
        assertThat(revision.getEditedBy().getId()).isEqualTo(owner.getId());
        assertThat(revision.getReleaseNote().getId()).isEqualTo(note.getId());
        assertThat(revision.getCreatedAt()).isNotNull();
        assertThat(revision.getId()).isNotNull();
    }
}
