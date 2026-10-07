package net.java21.blog.backend.external.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.portal.ExternalPortalQueryRepository;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.external.repository.ExternalReportPreviewRepository;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.dto.ReportTargetPreview.State;
import net.java21.blog.backend.report.dto.TargetKey;
import net.java21.blog.backend.report.repository.ReportTargetPreviewRepository;
import net.java21.blog.backend.report.service.ReportTarget;
import net.java21.blog.backend.report.service.ReportTargetHandlers;
import net.java21.blog.backend.report.service.ReportUrlResolver;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.querydsl.jpa.impl.JPAQueryFactory;

/**
 * 007 T076: 외부 글·외부 블로그 신고 처리기와 관리자 미리보기, 권리 침해 주소 해석(US4 AS3, FR-127·129, research E17). H2.
 */
@JpaRepositoryTest
@Import({ExternalPortalQueryRepository.class, ExternalReportPreviewRepository.class, QueryCounter.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ExternalReportHandlersTest {

    private static final String BASE = "https://blog.example.test";

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private ExternalPortalQueryRepository portalQueries;
    @Autowired
    private ExternalReportPreviewRepository externalPreviews;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private QueryCounter queryCounter;

    private ExternalPostReportHandler postHandler;
    private ExternalBlogReportHandler blogHandler;
    private ExternalFixtures x;
    private JpaFixtures f;
    private Topic topic;
    private User manager;
    private User reporter;
    private ExternalBlog blog;
    private ExternalPost post;

    @BeforeEach
    void setUp() {
        f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
        manager = f.user("manager");
        reporter = f.user("reporter");
        blog = x.blog(manager, topic, ExternalBlogStatus.ACTIVE);
        post = x.post(blog, "Visible", topic, null);
        em.flush();
        postHandler = new ExternalPostReportHandler(postRepository, portalQueries,
                new MutableClock(JpaFixtures.T0.plusSeconds(60)));
        blogHandler = new ExternalBlogReportHandler(blogRepository, postRepository);
    }

    @Test
    void externalPostMustBeOnPortalAndNotOwn() {
        ReportTarget target = postHandler.resolveForReporter(post.getId(), reporter.getId());
        assertThat(target.type()).isEqualTo(ReportTargetType.EXTERNAL_POST);
        assertThat(target.targetUser().getId()).isEqualTo(manager.getId());
        assertThat(target.targetBlog()).isNull();

        BusinessException own = catchThrowableOfType(BusinessException.class,
                () -> postHandler.resolveForReporter(post.getId(), manager.getId()));
        assertThat(own.errorCode()).isEqualTo(ErrorCode.CANNOT_REPORT_OWN_CONTENT);

        ExternalPost removed = x.removed(blog, "Removed", topic, RemovedReason.ADMIN);
        ExternalPost excluded = x.post(blog, "Excluded", topic, null);
        em.persist(new PortalExclusion(excluded, "x", manager));
        em.flush();
        for (long id : List.of(removed.getId(), excluded.getId(), 999_999L)) {
            BusinessException hidden = catchThrowableOfType(BusinessException.class,
                    () -> postHandler.resolveForReporter(id, reporter.getId()));
            assertThat(hidden.errorCode()).isEqualTo(ErrorCode.EXTERNAL_POST_NOT_FOUND);
        }
        // 관리자는 내린 글도 대상으로 지정할 수 있다(운영자 등록 블로그면 대상 회원 없음)
        assertThat(postHandler.resolveForAdmin(removed.getId()).id()).isEqualTo(removed.getId());
        ExternalBlog direct = x.blog(null, topic, ExternalBlogStatus.ACTIVE);
        ExternalPost directPost = x.post(direct, "Direct", topic, null);
        em.flush();
        assertThat(postHandler.resolveForReporter(directPost.getId(), reporter.getId()).targetUser()).isNull();
        BusinessException missing = catchThrowableOfType(BusinessException.class,
                () -> postHandler.resolveForAdmin(999_999L));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND);
    }

    @Test
    void keptPostsOfReleasedBlogStayReportable() {
        ExternalFixtures.moveTo(blog, ExternalBlogStatus.RELEASED);
        em.flush();
        assertThat(postHandler.resolveForReporter(post.getId(), reporter.getId()).id()).isEqualTo(post.getId());
        assertThat(blogHandler.resolveForReporter(blog.getId(), reporter.getId()).id()).isEqualTo(blog.getId());

        ExternalBlog releasedEmpty = x.blog(manager, topic, ExternalBlogStatus.RELEASED);
        ExternalBlog blocked = x.blog(manager, topic, ExternalBlogStatus.BLOCKED);
        ExternalBlog pending = x.blog(manager, topic, ExternalBlogStatus.PENDING);
        em.flush();
        for (ExternalBlog notReportable : List.of(releasedEmpty, blocked, pending)) {
            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> blogHandler.resolveForReporter(notReportable.getId(), reporter.getId()));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
        }
        BusinessException own = catchThrowableOfType(BusinessException.class,
                () -> blogHandler.resolveForReporter(blog.getId(), manager.getId()));
        assertThat(own.errorCode()).isEqualTo(ErrorCode.CANNOT_REPORT_OWN_CONTENT);
        assertThat(blogHandler.resolveForAdmin(blocked.getId()).targetUser().getId()).isEqualTo(manager.getId());
        assertThat(catchThrowableOfType(BusinessException.class, () -> blogHandler.resolveForAdmin(999_999L))
                .errorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND);
    }

    @Test
    void hideAndUnhideAreNotSupported() {
        for (Runnable call : List.<Runnable>of(() -> postHandler.hide(post.getId()),
                () -> postHandler.unhide(post.getId()), () -> blogHandler.hide(blog.getId()),
                () -> blogHandler.unhide(blog.getId()))) {
            BusinessException e = catchThrowableOfType(BusinessException.class, call::run);
            assertThat(e.errorCode()).isEqualTo(ErrorCode.REPORT_ACTION_NOT_ALLOWED);
        }
    }

    @Test
    void rightsRequestUrlResolvesVisitAddressAndOriginalLink() {
        ReportTargetHandlers handlers = new ReportTargetHandlers(List.of(postHandler, blogHandler));
        ReportUrlResolver resolver = new ReportUrlResolver(new SiteProperties(BASE), handlers, List.of(postHandler));

        assertThat(resolver.resolve(BASE + "/api/v1/external-posts/" + post.getId() + "/visit"))
                .hasValueSatisfying(t -> assertThat(t.id()).isEqualTo(post.getId()));
        assertThat(resolver.resolve(post.getLink())).hasValueSatisfying(t -> {
            assertThat(t.type()).isEqualTo(ReportTargetType.EXTERNAL_POST);
            assertThat(t.id()).isEqualTo(post.getId());
        });
        assertThat(resolver.resolve("https://unknown.example/post")).isEmpty();
        assertThat(resolver.resolve("ftp://post.example/x")).isEmpty();
        assertThat(resolver.resolve(BASE + "/api/v1/external-posts/999999/visit")).isEmpty();
        assertThat(postHandler.resolveUrl(URI.create("/relative"), URI.create(BASE))).isEmpty();
    }

    @Test
    void previewsUseOneQueryPerType() {
        ExternalPost removed = x.removed(blog, "Removed", topic, RemovedReason.REPORT);
        ExternalBlog blocked = x.blog(null, topic, ExternalBlogStatus.BLOCKED);
        em.flush();
        em.clear();
        ReportTargetPreviewRepository previews = new ReportTargetPreviewRepository(queryFactory,
                new SiteProperties(BASE), List.of(externalPreviews));
        List<TargetKey> keys = List.of(new TargetKey(ReportTargetType.EXTERNAL_POST, post.getId()),
                new TargetKey(ReportTargetType.EXTERNAL_POST, removed.getId()),
                new TargetKey(ReportTargetType.EXTERNAL_POST, 999_999L),
                new TargetKey(ReportTargetType.EXTERNAL_BLOG, blog.getId()),
                new TargetKey(ReportTargetType.EXTERNAL_BLOG, blocked.getId()));

        queryCounter.reset();
        Map<TargetKey, ReportTargetPreview> result = previews.previews(keys);

        assertThat(queryCounter.count()).isEqualTo(2);
        ReportTargetPreview visible = result.get(keys.get(0));
        assertThat(visible.state()).isEqualTo(State.ACTIVE);
        assertThat(visible.title()).isEqualTo("Visible");
        assertThat(visible.text()).isEqualTo("summary Visible");
        assertThat(visible.url()).isEqualTo(post.getLink());
        assertThat(visible.author().userId()).isEqualTo(manager.getId());
        assertThat(visible.blog().handle()).isNull();
        assertThat(visible.blog().title()).isEqualTo(blog.getTitle());
        assertThat(result.get(keys.get(1)).state()).isEqualTo(State.DELETED);
        assertThat(result.get(keys.get(2)).state()).isEqualTo(State.MISSING);
        ReportTargetPreview blogPreview = result.get(keys.get(3));
        assertThat(blogPreview.state()).isEqualTo(State.ACTIVE);
        assertThat(blogPreview.text()).isEqualTo(blog.getFeedUrl());
        assertThat(blogPreview.url()).isEqualTo(blog.getSiteUrl());
        ReportTargetPreview blockedPreview = result.get(keys.get(4));
        assertThat(blockedPreview.state()).isEqualTo(State.DELETED);
        assertThat(blockedPreview.author()).isNull();
        assertThat(externalPreviews.types()).isEqualTo(Set.of(ReportTargetType.EXTERNAL_POST,
                ReportTargetType.EXTERNAL_BLOG));
    }
}
