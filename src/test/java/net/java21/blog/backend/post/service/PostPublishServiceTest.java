package net.java21.blog.backend.post.service;

import static net.java21.blog.backend.post.service.PostDraftServiceTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.StatsProperties;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.repository.CategoryRepository;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.service.MediaReferenceService;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.content.HtmlSanitizerPolicy;
import net.java21.blog.backend.content.MarkdownRenderer;
import net.java21.blog.backend.content.VideoEmbedTransformer;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PublishSettingsRequest;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import net.java21.blog.backend.post.repository.PostQueryRepository;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.tag.service.TagService;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.topic.service.TopicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 발행·수정 발행(T061, FR-013, FR-015, FR-070, FR-107·108, AS6·7). */
@ExtendWith(MockitoExtension.class)
class PostPublishServiceTest {

    @Mock
    private net.java21.blog.backend.spam.RateLimitPolicy rateLimits;

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final String KEY_A = "k3Jd9fQ2xLmA7pZ0bR5tYw";
    private static final String KEY_B = "AAAAAAAAAAAAAAAAAAAAAA";
    static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(4);

    @Mock
    private PostRepository postRepository;
    @Mock
    private PostDraftRepository postDraftRepository;
    @Mock
    private PostQueryRepository postQueryRepository;
    @Mock
    private BlogRepository blogRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private TagQueryRepository tagQueryRepository;
    @Mock
    private TagService tagService;
    @Mock
    private MediaReferenceService mediaReferences;
    @Mock
    private TopicRepository topicRepository;

    private PostPublishService service;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PostAccess access = new PostAccess(postRepository);
        CategoryAccess categoryAccess = new CategoryAccess(categoryRepository);
        PostService postService = new PostService(postRepository, postDraftRepository, postQueryRepository, access,
                new BlogAccess(blogRepository), categoryAccess, tagQueryRepository,
                org.mockito.Mockito.mock(net.java21.blog.backend.like.repository.PostLikeRepository.class), clock,
                new BlogCalendar(StatsProperties.defaults(), clock));
        service = new PostPublishService(access, postDraftRepository,
                new MarkdownRenderer(new HtmlSanitizerPolicy(), new VideoEmbedTransformer()), postService,
                categoryAccess, tagService, mediaReferences,
                new TopicService(topicRepository, null, null, null, null, null, null), PASSWORD_ENCODER,
                PostsProperties.defaults(), rateLimits, clock);
        blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        post = TestEntities.post(100L, blog, "제목");
        lenient().when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
    }

    @Test
    void publishRendersSanitizesAppliesAndDeletesDraftCopy() {
        PostDraft draft = draft("  제목  ", "# 안녕\n\n<script>alert(1)</script>\n\n본문 ![a](/media/" + KEY_A + ")");
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        PostDetailResponse detail = service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));

        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.getTitle()).isEqualTo("제목");
        assertThat(post.getContentMarkdown()).startsWith("# 안녕");
        assertThat(post.getContentHtml()).contains("<h1>안녕</h1>").doesNotContain("script", "alert");
        assertThat(post.getContentText()).startsWith("안녕 본문");
        assertThat(post.getSummary()).isEqualTo(post.getContentText());
        assertThat(post.getThumbnailUrl()).isEqualTo("/media/" + KEY_A);
        assertThat(post.isCommentEnabled()).isTrue();
        assertThat(post.getPublishedAt()).isEqualTo(NOW);
        verify(postDraftRepository).delete(draft);
        assertThat(detail.id()).isEqualTo(100L);
        assertThat(detail.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(detail.contentMarkdown()).isNotNull();
        assertThat(detail.blogHandle()).isEqualTo("marco");
    }

    /** 005 T069: 처음 발행(DRAFT → PUBLISHED·SCHEDULED)만 회원 1시간 한도로 센다. 수정 재발행은 세지 않는다. 관리자 제외. */
    @Test
    void onlyFirstPublishIsCountedAgainstTheHourlyLimit() {
        draft("제목", "본문");
        lenient().when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        lenient().when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));
        verify(rateLimits).check(net.java21.blog.backend.spam.RateLimitKind.POST_PUBLISH, "u:1");

        draft("새 제목", "새 본문");
        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));
        verify(rateLimits, org.mockito.Mockito.times(1)).check(any(), any());
    }

    @Test
    void publishOverTheLimitIs429AndPostStaysDraft() {
        draft("제목", "본문");
        org.mockito.Mockito.doThrow(net.java21.blog.backend.common.error.BusinessException.retryAfter(
                net.java21.blog.backend.common.error.ErrorCode.TOO_MANY_REQUESTS, "x", 100)).when(rateLimits)
                .check(net.java21.blog.backend.spam.RateLimitKind.POST_PUBLISH, "u:1");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)))
                .isInstanceOf(net.java21.blog.backend.common.error.BusinessException.class);
        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    void adminsAreNotCounted() {
        TestEntities.with(blog.getUser(), "role", net.java21.blog.backend.user.domain.UserRole.ADMIN);
        draft("제목", "본문");
        lenient().when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        lenient().when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));
        org.mockito.Mockito.verifyNoInteractions(rateLimits);
    }

    @Test
    void republishKeepsIdAndFirstPublishedAt() {
        Instant first = NOW.minusSeconds(86_400);
        post.publish("옛 제목", "옛 본문", "<p>옛 본문</p>", "옛 본문", "옛 본문", null, PostVisibility.PUBLIC, true, first);
        draft("새 제목", "새 본문");
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        PostDetailResponse detail = service.publish(1L, 100L, settings(PostVisibility.PRIVATE, null, false));

        assertThat(detail.id()).isEqualTo(100L);
        assertThat(post.getPublishedAt()).isEqualTo(first);
        assertThat(post.getTitle()).isEqualTo("새 제목");
        assertThat(post.getVisibility()).isEqualTo(PostVisibility.PRIVATE);
        assertThat(post.isCommentEnabled()).isFalse();
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
    }

    @Test
    void republishWithoutDraftUsesCurrentContent() {
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PRIVATE, true, NOW);
        when(postDraftRepository.findById(100L)).thenReturn(Optional.empty());
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));

        assertThat(post.getVisibility()).isEqualTo(PostVisibility.PUBLIC);
        assertThat(post.getContentHtml()).isEqualTo("<p>본문</p>\n");
        verify(postDraftRepository, never()).delete(any());
    }

    /** 005 T033: 관리자가 숨긴 글은 다시 발행할 수 없다(409 POST_HIDDEN, 상태 그대로). */
    @Test
    void hiddenPostCannotBeRepublished() {
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW);
        post.hide();

        assertCode(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)), ErrorCode.POST_HIDDEN);
        assertThat(post.isHidden()).isTrue();
        verify(postDraftRepository, never()).findById(any());
    }

    /** 003 T039: 블로그의 첫 발행이 {@code first_published_at}을 채운다(비공개 발행 포함). */
    @Test
    void firstPublishOfTheBlogSetsFirstPublishedAtEvenWhenPrivate() {
        draft("제목", "본문");
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        service.publish(1L, 100L, settings(PostVisibility.PRIVATE, null, null));

        assertThat(blog.getFirstPublishedAt()).isEqualTo(NOW);
    }

    /** 004 T050: 공지 true·false·null(유지), 새 글 기본 false. */
    @Test
    void noticeIsSetClearedOrKept() {
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        assertThat(post.isNotice()).isFalse();

        draft("제목", "본문");
        service.publish(1L, 100L, notice(true));
        assertThat(post.isNotice()).isTrue();
        draft("제목", "본문");
        assertThat(service.publish(1L, 100L, notice(null)).notice()).isTrue();
        draft("제목", "본문");
        service.publish(1L, 100L, notice(false));
        assertThat(post.isNotice()).isFalse();
    }

    private static PublishSettingsRequest notice(Boolean notice) {
        return new PublishSettingsRequest(PostVisibility.PUBLIC, null, null, null, null, null, notice);
    }

    /** 003 T039: 이미 값이 있으면(두 번째 글·수정 발행) 그대로 둔다. */
    @Test
    void laterPublishesKeepTheBlogsFirstPublishedAt() {
        Instant earlier = NOW.minusSeconds(86_400);
        blog.markFirstPublished(earlier);
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, earlier);
        draft("새 제목", "새 본문");
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));

        assertThat(blog.getFirstPublishedAt()).isEqualTo(earlier);
    }

    @Test
    void titleIsRequired() {
        draft("   ", "본문");

        assertThatThrownBy(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement()
                            .satisfies(f -> assertThat(f.field() + ":" + f.code()).isEqualTo("title:REQUIRED"));
                });
        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    void titleLongerThan200IsRejected() {
        draft("가".repeat(201), "본문");

        assertThatThrownBy(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors().getFirst().code())
                        .isEqualTo("TOO_LONG"));
    }

    @Test
    void emptyContentIsUnprocessable() {
        draft("제목", "  \n ");
        assertCode(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)),
                ErrorCode.POST_CONTENT_EMPTY);
        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    void chosenThumbnailMustBeAnImageInTheBody() {
        draft("제목", "![a](/media/" + KEY_A + ") ![b](/media/" + KEY_B + ")");
        when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, KEY_B, null));
        assertThat(post.getThumbnailUrl()).isEqualTo("/media/" + KEY_B);
    }

    @Test
    void thumbnailNotInBodyIsRejected() {
        draft("제목", "그림 없음");

        assertThatThrownBy(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, KEY_A, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors().getFirst().field())
                        .isEqualTo("thumbnailMediaKey"));
    }

    @Test
    void othersCannotPublish() {
        assertCode(() -> service.publish(2L, 100L, settings(PostVisibility.PUBLIC, null, null)), ErrorCode.FORBIDDEN);
    }

    @Test
    void trashedPostCannotBePublished() {
        post.moveToTrash(NOW);
        assertCode(() -> service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null)),
                ErrorCode.POST_NOT_FOUND);
    }

    @Test
    void publishedPostNeverGoesBackToDraft() {
        post.publish("t", "b", "<p>b</p>", "b", "b", null, PostVisibility.PUBLIC, true, NOW);
        post.moveToTrash(NOW);
        post.restore();
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThatThrownBy(() -> TestEntities.post(1L, blog, "x").restore()).isInstanceOf(IllegalStateException.class);
    }

    // ---- 주제(003 T069, FR-076, research P9) ----

    private Topic major;
    private Topic minor;

    private void topics() {
        major = TestEntities.topic(3L, null, "knowledge");
        minor = TestEntities.topic(31L, major, "it-internet");
        lenient().when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        lenient().when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
    }

    @Test
    void topicFromSettingsWinsOverDraft() {
        topics();
        draft("제목", "본문").changeTopic(99L);
        when(topicRepository.findWithParent(31L)).thenReturn(Optional.of(minor));

        PostDetailResponse detail = service.publish(1L, 100L, topicSettings(31L));

        assertThat(post.getTopic()).isSameAs(minor);
        assertThat(detail.topicId()).isEqualTo(31L);
    }

    @Test
    void topicFallsBackToDraftThenToPublishedValue() {
        topics();
        draft("제목", "본문").changeTopic(31L);
        when(topicRepository.findWithParent(31L)).thenReturn(Optional.of(minor));
        service.publish(1L, 100L, topicSettings(null));
        assertThat(post.getTopic()).isSameAs(minor);

        // 사본이 없으면 지금 발행본의 주제를 그대로(검사 없이) 쓴다.
        when(postDraftRepository.findById(100L)).thenReturn(Optional.empty());
        when(topicRepository.getReferenceById(31L)).thenReturn(minor);
        service.publish(1L, 100L, topicSettings(null));
        assertThat(post.getTopicId()).isEqualTo(31L);
    }

    @Test
    void draftWithoutTopicPublishesWithoutTopic() {
        topics();
        post.assignTopic(minor);
        draft("제목", "본문");

        service.publish(1L, 100L, topicSettings(null));

        assertThat(post.getTopic()).isNull();
    }

    @Test
    void majorOrHiddenTopicIsNotSelectableAndPostIsUnchanged() {
        topics();
        draft("제목", "본문");
        Topic hidden = TestEntities.topic(32L, major, "mobile");
        hidden.hide();
        Topic hiddenParent = TestEntities.topic(4L, null, "sports");
        hiddenParent.hide();
        Topic underHidden = TestEntities.topic(41L, hiddenParent, "golf");
        when(topicRepository.findWithParent(3L)).thenReturn(Optional.of(major));
        when(topicRepository.findWithParent(32L)).thenReturn(Optional.of(hidden));
        when(topicRepository.findWithParent(41L)).thenReturn(Optional.of(underHidden));
        when(topicRepository.findWithParent(77L)).thenReturn(Optional.empty());

        assertCode(() -> service.publish(1L, 100L, topicSettings(3L)), ErrorCode.TOPIC_NOT_SELECTABLE);
        assertCode(() -> service.publish(1L, 100L, topicSettings(32L)), ErrorCode.TOPIC_NOT_SELECTABLE);
        assertCode(() -> service.publish(1L, 100L, topicSettings(41L)), ErrorCode.TOPIC_NOT_SELECTABLE);
        assertCode(() -> service.publish(1L, 100L, topicSettings(77L)), ErrorCode.TOPIC_NOT_FOUND);
        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(post.getTopic()).isNull();
        verify(postDraftRepository, never()).delete(any());
    }

    @Test
    void republishWithSameLaterHiddenTopicPasses() {
        topics();
        minor.hide();
        post.publish("t", "b", "<p>b</p>", "b", "b", null, PostVisibility.PUBLIC, true, NOW);
        post.assignTopic(minor);
        draft("제목", "본문").changeTopic(31L);
        when(topicRepository.getReferenceById(31L)).thenReturn(minor);

        service.publish(1L, 100L, topicSettings(31L));

        assertThat(post.getTopic()).isSameAs(minor);
    }

    // ---- 004 US3 T075: 보호 글·예약 발행 ----

    private void noNeighbours() {
        lenient().when(postQueryRepository.findPrevious(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        lenient().when(postQueryRepository.findNext(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
    }

    private static PublishSettingsRequest protect(String password) {
        return new PublishSettingsRequest(PostVisibility.PROTECTED, null, null, null, null, null, null, password,
                null);
    }

    private static PublishSettingsRequest scheduleAt(Instant at) {
        return new PublishSettingsRequest(PostVisibility.PUBLIC, null, null, null, null, null, null, null, at);
    }

    @Test
    void protectedNeedsPasswordWhenNewlyProtected() {
        draft("제목", "본문");
        assertCode(() -> service.publish(1L, 100L, protect(null)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.publish(1L, 100L, protect("")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.publish(1L, 100L, protect("123")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.publish(1L, 100L, protect("x".repeat(65))), ErrorCode.VALIDATION_FAILED);
        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(post.getPasswordHash()).isNull();
    }

    @Test
    void passwordErrorsNameTheField() {
        draft("제목", "본문");
        assertThatThrownBy(() -> service.publish(1L, 100L, protect(null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.fieldErrors()).singleElement().satisfies(f -> {
                        assertThat(f.field()).isEqualTo("password");
                        assertThat(f.code()).isEqualTo("REQUIRED");
                    });
                });
        assertThatThrownBy(() -> service.publish(1L, 100L, protect("123")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.fieldErrors().getFirst().code()).isEqualTo("TOO_SHORT"));
        assertThatThrownBy(() -> service.publish(1L, 100L, protect("x".repeat(65))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.fieldErrors().getFirst().code()).isEqualTo("TOO_LONG"));
    }

    @Test
    void protectedStoresBcryptKeepsHashWhenOmittedAndClearsOnOtherVisibility() {
        noNeighbours();
        draft("제목", "본문");
        PostDetailResponse detail = service.publish(1L, 100L, protect("secret1"));
        assertThat(detail.visibility()).isEqualTo(PostVisibility.PROTECTED);
        assertThat(post.getPasswordHash()).startsWith("$2").doesNotContain("secret1");
        assertThat(PASSWORD_ENCODER.matches("secret1", post.getPasswordHash())).isTrue();
        String first = post.getPasswordHash();

        draft("제목", "본문");
        service.publish(1L, 100L, protect(null));
        assertThat(post.getPasswordHash()).isEqualTo(first);

        draft("제목", "본문");
        service.publish(1L, 100L, protect("another"));
        assertThat(PASSWORD_ENCODER.matches("another", post.getPasswordHash())).isTrue();

        draft("제목", "본문");
        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));
        assertThat(post.getPasswordHash()).isNull();
        assertThat(post.getVisibility()).isEqualTo(PostVisibility.PUBLIC);
    }

    @Test
    void futureScheduledAtSchedulesWithoutPublishing() {
        noNeighbours();
        PostDraft copy = draft("제목", "예약 ![a](/media/" + KEY_A + ")");
        Instant at = NOW.plusSeconds(600);

        PostDetailResponse detail = service.publish(1L, 100L, scheduleAt(at));

        assertThat(post.getStatus()).isEqualTo(PostStatus.SCHEDULED);
        assertThat(post.getScheduledAt()).isEqualTo(at);
        assertThat(post.getPublishedAt()).isNull();
        assertThat(post.getContentHtml()).contains("예약");
        assertThat(blog.getFirstPublishedAt()).isNull();
        verify(postDraftRepository).delete(copy);
        verify(mediaReferences).syncPublished(100L, 1L, copy.getContentMarkdown());
        assertThat(detail.status()).isEqualTo(PostStatus.SCHEDULED);
        assertThat(detail.scheduledAt()).isEqualTo(at);
        assertThat(detail.prev()).isNull();
    }

    @Test
    void pastOrPresentScheduledAtPublishesImmediately() {
        noNeighbours();
        draft("제목", "본문");
        service.publish(1L, 100L, scheduleAt(NOW.minusSeconds(3600)));
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.getPublishedAt()).isEqualTo(NOW);
        assertThat(post.getScheduledAt()).isNull();
    }

    @Test
    void publishingScheduledPostNowClearsScheduledAt() {
        noNeighbours();
        draft("제목", "본문");
        service.publish(1L, 100L, scheduleAt(NOW.plusSeconds(600)));
        draft("제목", "본문");
        service.publish(1L, 100L, scheduleAt(NOW.plusSeconds(1200)));
        assertThat(post.getScheduledAt()).isEqualTo(NOW.plusSeconds(1200));

        draft("제목", "본문");
        service.publish(1L, 100L, settings(PostVisibility.PUBLIC, null, null));
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.getScheduledAt()).isNull();
        assertThat(blog.getFirstPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void publishedPostCannotBeScheduled() {
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW);
        draft("새 제목", "새 본문");
        assertCode(() -> service.publish(1L, 100L, scheduleAt(NOW.plusSeconds(600))),
                ErrorCode.SCHEDULE_NOT_ALLOWED);
        assertThat(post.getTitle()).isEqualTo("제목");
    }

    @Test
    void scheduleBeyondMaxAheadIsInvalid() {
        draft("제목", "본문");
        assertThatThrownBy(() -> service.publish(1L, 100L, scheduleAt(NOW.plus(java.time.Duration.ofDays(366)))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors().getFirst().field()).isEqualTo("scheduledAt");
                    assertThat(e.fieldErrors().getFirst().code()).isEqualTo("INVALID");
                    assertThat(e.fieldErrors().getFirst().params()).containsEntry("max", 365L);
                });
        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    void settingsToStringHidesPassword() {
        assertThat(protect("secret1").toString()).doesNotContain("secret1").contains("****");
    }

    private static PublishSettingsRequest topicSettings(Long topicId) {
        return new PublishSettingsRequest(PostVisibility.PUBLIC, null, null, null, null, topicId);
    }

    private PostDraft draft(String title, String markdown) {
        PostDraft draft = new PostDraft(post);
        draft.write(title, markdown, null, null, NOW);
        when(postDraftRepository.findById(100L)).thenReturn(Optional.of(draft));
        return draft;
    }

    private static PublishSettingsRequest settings(PostVisibility visibility, String thumbnailKey, Boolean comments) {
        return new PublishSettingsRequest(visibility, thumbnailKey, comments, null, null, null);
    }
}
