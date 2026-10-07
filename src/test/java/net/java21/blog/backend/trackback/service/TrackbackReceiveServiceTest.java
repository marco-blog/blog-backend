package net.java21.blog.backend.trackback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.report.ReportsProperties;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.spam.RateLimiter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.trackback.TrackbackUrls;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.repository.TrackbackRepository;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 트랙백 받기 규칙(005 T084, FR-049~051·053~055, research M13, AS3·AS5, Edge Cases). */
@ExtendWith(MockitoExtension.class)
class TrackbackReceiveServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final String IP = "203.0.113.7";
    private static final PingForm FORM = new PingForm("https://other.example/post/1", "다른 글", "요약",
            "다른 블로그");

    @Mock
    private PostRepository postRepository;
    @Mock
    private TrackbackRepository trackbackRepository;

    private TrackbackReceiveService service;
    private RateLimiter limiter;
    private User owner;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter();
        RateLimitPolicy policy = new RateLimitPolicy(limiter, mock(SystemSettingsService.class),
                ReportsProperties.defaults(), TrackbackProperties.defaults());
        service = new TrackbackReceiveService(postRepository, trackbackRepository, policy,
                new TrackbackUrls(new SiteProperties("https://blog.java21.net")),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        owner = TestEntities.user(1L);
        blog = TestEntities.blog(10L, owner, "marco");
        post = published(100L, blog, PostVisibility.PUBLIC);
        lenient().when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        lenient().when(trackbackRepository.saveAndFlush(any(Trackback.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static Post published(long id, Blog blog, PostVisibility visibility) {
        Post p = TestEntities.post(id, blog, "받는 글 " + id);
        p.publish("받는 글 " + id, "본문", "<p>본문</p>", "본문", "요약 " + id, null, visibility, true, NOW);
        return p;
    }

    @Test
    void storesCleanedPingInCheckOrder() {
        PingForm form = new PingForm(" https://Other.Example:443/post/1#frag ",
                "<b>제목</b>\u0000 &amp; 더", "<p>요약 <script>alert(1)</script>본문</p>", "<i>블로그</i>");

        assertThat(service.receive("marco", 100L, form, IP)).isEqualTo(ReceiveOutcome.ACCEPTED);

        InOrder order = inOrder(postRepository, trackbackRepository);
        order.verify(postRepository).findWithBlogAndOwner(100L);
        order.verify(trackbackRepository).existsByPostIdAndSourceUrlHash(100L,
                TrackbackUrls.hash("https://other.example/post/1"));
        ArgumentCaptor<Trackback> saved = ArgumentCaptor.forClass(Trackback.class);
        order.verify(trackbackRepository).saveAndFlush(saved.capture());
        Trackback t = saved.getValue();
        assertThat(t.getPost()).isSameAs(post);
        assertThat(t.getSourcePost()).isNull();
        assertThat(t.getSourceUrl()).isEqualTo("https://Other.Example:443/post/1#frag");
        assertThat(t.getSourceUrlHash()).isEqualTo(TrackbackUrls.hash("https://other.example/post/1"));
        assertThat(t.getTitle()).isEqualTo("제목 & 더");
        assertThat(t.getExcerpt()).isEqualTo("요약 본문");
        assertThat(t.getBlogName()).isEqualTo("블로그");
        assertThat(t.getSenderIp()).isEqualTo(IP);
        assertThat(t.isActive()).isTrue();
    }

    @Test
    void missingTitleFallsBackToUrlAndLongValuesAreCut() {
        PingForm form = new PingForm("https://other.example/" + "p".repeat(400), "  ", "가".repeat(300), null);

        assertThat(service.receive("marco", 100L, form, IP)).isEqualTo(ReceiveOutcome.ACCEPTED);

        ArgumentCaptor<Trackback> saved = ArgumentCaptor.forClass(Trackback.class);
        verify(trackbackRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTitle()).hasSize(Trackback.TEXT_MAX).startsWith("https://other.example/ppp");
        assertThat(saved.getValue().getExcerpt()).hasSize(Trackback.TEXT_MAX);
        assertThat(saved.getValue().getBlogName()).isNull();
    }

    @Test
    void unknownPostAndWrongHandleAreNotAllowed() {
        when(postRepository.findWithBlogAndOwner(999L)).thenReturn(Optional.empty());

        assertThat(service.receive("marco", 999L, FORM, IP)).isEqualTo(ReceiveOutcome.NOT_ALLOWED);
        assertThat(service.receive("someone", 100L, FORM, IP)).isEqualTo(ReceiveOutcome.NOT_ALLOWED);
        verify(trackbackRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "PROTECTED", "DRAFT", "SCHEDULED", "DELETED", "HIDDEN", "SUSPENDED",
            "BLOG_DELETED", "TRACKBACK_OFF"})
    void postsThatAreNotBodyVisibleOrBlogsWithTrackbacksOffAreNotAllowed(String state) {
        Post target = switch (state) {
            case "PRIVATE" -> published(100L, blog, PostVisibility.PRIVATE);
            case "PROTECTED" -> published(100L, blog, PostVisibility.PROTECTED);
            case "DRAFT" -> TestEntities.post(100L, blog, "임시");
            case "SCHEDULED" -> {
                Post p = TestEntities.post(100L, blog, "예약");
                p.schedule("예약", "본문", "<p>본문</p>", "본문", "요약", null, PostVisibility.PUBLIC, true,
                        NOW.plusSeconds(3600));
                yield p;
            }
            case "DELETED" -> {
                Post p = published(100L, blog, PostVisibility.PUBLIC);
                p.moveToTrash(NOW);
                yield p;
            }
            case "HIDDEN" -> {
                Post p = published(100L, blog, PostVisibility.PUBLIC);
                p.hide();
                yield p;
            }
            case "SUSPENDED" -> {
                owner.suspend();
                yield post;
            }
            case "BLOG_DELETED" -> {
                TestEntities.with(blog, "status", BlogStatus.DELETED);
                yield post;
            }
            default -> {
                blog.changeTrackbackEnabled(false);
                yield post;
            }
        };
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(target));

        assertThat(service.receive("marco", 100L, FORM, IP)).isEqualTo(ReceiveOutcome.NOT_ALLOWED);
        verify(trackbackRepository, never()).existsByPostIdAndSourceUrlHash(anyLong(), anyString());
        verify(trackbackRepository, never()).saveAndFlush(any());
    }

    @Test
    void urlIsCheckedAfterThePost() {
        assertThat(service.receive("marco", 100L, new PingForm(null, "t", null, null), IP))
                .isEqualTo(ReceiveOutcome.MISSING_URL);
        assertThat(service.receive("marco", 100L, new PingForm("  ", "t", null, null), IP))
                .isEqualTo(ReceiveOutcome.MISSING_URL);
        assertThat(service.receive("marco", 100L, new PingForm("javascript:alert(1)", "t", null, null), IP))
                .isEqualTo(ReceiveOutcome.INVALID_URL);
        assertThat(service.receive("marco", 100L,
                new PingForm("https://e.com/" + "a".repeat(1000), "t", null, null), IP))
                .isEqualTo(ReceiveOutcome.INVALID_URL);
        blog.changeTrackbackEnabled(false);
        assertThat(service.receive("marco", 100L, new PingForm(null, "t", null, null), IP))
                .as("트랙백을 끈 블로그는 url보다 먼저 거부").isEqualTo(ReceiveOutcome.NOT_ALLOWED);
        verify(trackbackRepository, never()).saveAndFlush(any());
    }

    @Test
    void sameAddressAgainIncludingDeletedOrHiddenRowsIsDuplicate() {
        when(trackbackRepository.existsByPostIdAndSourceUrlHash(100L,
                TrackbackUrls.hash("https://other.example/post/1"))).thenReturn(true);

        assertThat(service.receive("marco", 100L, new PingForm("HTTPS://other.example/post/1#x", null, null, null),
                IP)).isEqualTo(ReceiveOutcome.DUPLICATE);
        verify(trackbackRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentUniqueViolationIsReportedAsDuplicate() {
        when(trackbackRepository.saveAndFlush(any(Trackback.class)))
                .thenThrow(new DataIntegrityViolationException("uk_trackbacks_post_source_url_hash"));

        assertThat(service.receive("marco", 100L, FORM, IP)).isEqualTo(ReceiveOutcome.DUPLICATE);
    }

    @Test
    void eleventhPingFromTheSameIpWithinTenMinutesIsRefusedBeforeAnyLookup() {
        for (int i = 0; i < 10; i++) {
            assertThat(service.receive("marco", 100L, new PingForm("https://other.example/" + i, null, null, null),
                    IP)).isEqualTo(ReceiveOutcome.ACCEPTED);
        }

        assertThat(service.receive("marco", 100L, FORM, IP)).isEqualTo(ReceiveOutcome.TOO_MANY_PINGS);
        assertThat(service.receive("marco", 999L, FORM, IP)).as("글을 보기 전에 센다")
                .isEqualTo(ReceiveOutcome.TOO_MANY_PINGS);
        assertThat(service.receive("marco", 100L, FORM, "198.51.100.1")).as("다른 IP는 따로 센다")
                .isEqualTo(ReceiveOutcome.ACCEPTED);
        verify(postRepository, never()).findWithBlogAndOwner(999L);
    }

    @Test
    void internalPingFillsSourcePostAndSkipsTheIpLimit() {
        Blog otherBlog = TestEntities.blog(20L, TestEntities.user(2L), "other");
        otherBlog.changeTitle("다른 블로그");
        Post source = published(200L, otherBlog, PostVisibility.PUBLIC);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(net.java21.blog.backend.spam.RateLimitKind.TRACKBACK_RECEIVE, "ip:null", 10,
                    java.time.Duration.ofMinutes(10));
        }

        assertThat(service.receiveInternal(source, 100L)).isEqualTo(ReceiveOutcome.ACCEPTED);

        ArgumentCaptor<Trackback> saved = ArgumentCaptor.forClass(Trackback.class);
        verify(trackbackRepository).saveAndFlush(saved.capture());
        Trackback t = saved.getValue();
        assertThat(t.getSourcePost()).isSameAs(source);
        assertThat(t.getSourceUrl()).isEqualTo("https://blog.java21.net/other/200");
        assertThat(t.getTitle()).isEqualTo("받는 글 200");
        assertThat(t.getExcerpt()).isEqualTo("요약 200");
        assertThat(t.getBlogName()).isEqualTo("다른 블로그");
        assertThat(t.getSenderIp()).isNull();
    }

    @Test
    void internalPingFollowsTheSameRules() {
        Post source = published(200L, TestEntities.blog(20L, TestEntities.user(2L), "other"), PostVisibility.PUBLIC);

        assertThat(service.receiveInternal(source, "wrong", 100L)).isEqualTo(ReceiveOutcome.NOT_ALLOWED);
        assertThat(service.receiveInternal(post, 100L)).as("자기 자신").isEqualTo(ReceiveOutcome.NOT_ALLOWED);
        blog.changeTrackbackEnabled(false);
        assertThat(service.receiveInternal(source, "marco", 100L)).isEqualTo(ReceiveOutcome.NOT_ALLOWED);
        verify(trackbackRepository, never()).saveAndFlush(any());
    }
}
