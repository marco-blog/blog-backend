package net.java21.blog.backend.post.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.content.MarkdownRenderer;
import net.java21.blog.backend.content.RenderedContent;
import net.java21.blog.backend.media.service.MediaReferenceService;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PublishSettingsRequest;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import net.java21.blog.backend.tag.domain.TagNormalizer;
import net.java21.blog.backend.tag.service.TagService;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.service.TopicService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행·수정 발행(FR-013, FR-015, FR-070, FR-107·108). 작성 중 사본(없으면 지금 발행본)을 HTML로 바꾸고 살균해 {@code posts}에
 * 반영한 뒤 사본을 지운다. 제목 1~200자 필수, 본문이 비면 422 {@code POST_CONTENT_EMPTY}.
 * 글 번호는 바뀌지 않고 {@code published_at}은 처음 발행할 때만 정한다.
 * <p>카테고리·태그(FR-024·025, T169): 발행 설정에 값이 있으면 그것을, 없으면(null) 작성 중 사본의 값을, 사본도 없으면 지금 발행본의
 * 값을 쓴다. 카테고리는 같은 블로그의 것이어야 하고(아니면 404 {@code CATEGORY_NOT_FOUND}), 태그는 정규화·검증한 뒤
 * {@code post_tags}를 통째로 바꾼다(글당 10개 초과는 422 {@code TAG_LIMIT_EXCEEDED}). 검증은 글을 바꾸기 전에 모두 한다.
 * <p>주제(003 FR-076, research P9): 카테고리와 같은 순서(발행 설정 → 사본 → 발행본)로 고르고, 지금 발행본과 다른 값이면
 * 소분류이고 운영자 숨김이 아니어야 한다(없으면 404 {@code TOPIC_NOT_FOUND}, 아니면 422 {@code TOPIC_NOT_SELECTABLE}).
 * 사본의 {@code null}은 "선택 안 함"이다.
 * <p>발행 때 본문의 이미지 참조를 PUBLISHED로 바꾸고 DRAFT 참조를 지운다(US4, FR-071·073).
 * <p>004 보호 글(FR-062, research B4): PROTECTED를 새로 지정하면 {@code password}(4~64자) 필수, 이미 보호 글이면 생략 시 해시 유지,
 * 다른 공개 범위로 바꾸면 해시를 지운다. BCrypt로만 저장한다.
 * <p>004 예약 발행(FR-064, research B5): {@code scheduledAt}이 지금보다 미래면 예약(DRAFT·SCHEDULED 글만, PUBLISHED면 422
 * {@code SCHEDULE_NOT_ALLOWED}, {@code blog.posts.schedule-max-ahead} 넘으면 400 {@code scheduledAt INVALID}). 예약은
 * {@code published_at}을 건드리지 않고 블로그 첫 발행 시각도 남기지 않는다(작업이 실제로 발행할 때 남긴다). 없거나 지금 이하면 즉시 발행.
 */
@Service
public class PostPublishService {

    static final int TITLE_MAX = 200;
    static final int PASSWORD_MIN = 4;
    static final int PASSWORD_MAX = 64;

    private final PostAccess postAccess;
    private final PostDraftRepository postDraftRepository;
    private final MarkdownRenderer markdownRenderer;
    private final PostService postService;
    private final CategoryAccess categoryAccess;
    private final TagService tagService;
    private final MediaReferenceService mediaReferences;
    private final TopicService topicService;
    private final PasswordEncoder passwordEncoder;
    private final PostsProperties properties;
    private final Clock clock;

    public PostPublishService(PostAccess postAccess, PostDraftRepository postDraftRepository,
            MarkdownRenderer markdownRenderer, PostService postService, CategoryAccess categoryAccess,
            TagService tagService, MediaReferenceService mediaReferences, TopicService topicService,
            PasswordEncoder passwordEncoder, PostsProperties properties, Clock clock) {
        this.postAccess = postAccess;
        this.postDraftRepository = postDraftRepository;
        this.markdownRenderer = markdownRenderer;
        this.postService = postService;
        this.categoryAccess = categoryAccess;
        this.tagService = tagService;
        this.mediaReferences = mediaReferences;
        this.topicService = topicService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public PostDetailResponse publish(long userId, Long postId, PublishSettingsRequest settings) {
        Post post = postAccess.requireOwnedEditablePost(postId, userId);
        if (post.isHidden()) {
            throw new BusinessException(ErrorCode.POST_HIDDEN, "Post is hidden by an administrator: " + postId);
        }
        PostDraft draft = postDraftRepository.findById(postId).orElse(null);
        String title = draft != null ? draft.getTitle() : post.getTitle();
        String markdown = draft != null ? draft.getContentMarkdown() : post.getContentMarkdown();

        title = title == null ? "" : title.strip();
        if (title.isEmpty()) {
            throw invalid(new FieldError("title", "REQUIRED", Map.of()));
        }
        if (title.length() > TITLE_MAX) {
            throw invalid(new FieldError("title", "TOO_LONG", Map.of("max", TITLE_MAX)));
        }
        if (markdown == null || markdown.isBlank()) {
            throw new BusinessException(ErrorCode.POST_CONTENT_EMPTY, "Post content is empty: " + postId);
        }

        Category category = category(post, draft, settings);
        List<String> rawTags = settings.tags() != null ? settings.tags() : draft != null ? draft.getTags() : null;
        List<String> tags = rawTags == null ? null : TagNormalizer.normalizeAll(rawTags, "tags");
        Topic topic = topic(post, draft, settings);
        Instant now = clock.instant();
        String passwordHash = passwordHash(post, settings);
        boolean schedule = schedule(post, settings.scheduledAt(), now);

        RenderedContent content = markdownRenderer.render(markdown);
        String thumbnailUrl = thumbnailUrl(content, settings.thumbnailMediaKey());
        boolean commentEnabled = settings.commentEnabled() == null || settings.commentEnabled();
        post.classify(category);
        post.assignTopic(topic);
        if (schedule) {
            post.schedule(title, markdown, content.html(), content.text(), content.summary(), thumbnailUrl,
                    settings.visibility(), commentEnabled, settings.scheduledAt());
        } else {
            post.publish(title, markdown, content.html(), content.text(), content.summary(), thumbnailUrl,
                    settings.visibility(), commentEnabled, now);
        }
        post.applyProtection(passwordHash);
        if (settings.notice() != null) {
            post.changeNotice(settings.notice());
        }
        if (!schedule) {
            // 003 FR-087 "새로 시작한 블로그": 블로그의 첫 발행(비공개 발행 포함)만 남긴다. 예약 글은 작업이 발행할 때 남긴다.
            post.getBlog().markFirstPublished(now);
        }
        if (draft != null) {
            postDraftRepository.delete(draft);
        }
        if (tags != null) {
            tagService.replacePostTags(post, tags);
        }
        postDraftRepository.flush();
        mediaReferences.syncPublished(postId, userId, markdown);
        return postService.detailOf(post, userId);
    }

    /**
     * 보호 글 비밀번호의 BCrypt 해시. PROTECTED가 아니거나 이미 보호 글이고 비밀번호를 생략했으면 null(해시 유지·삭제는
     * {@link Post#applyProtection}). 새로 보호 글로 바꾸는데 없으면 400 {@code password REQUIRED}, 4~64자.
     */
    private String passwordHash(Post post, PublishSettingsRequest settings) {
        if (settings.visibility() != PostVisibility.PROTECTED) {
            return null;
        }
        String password = settings.password();
        if (password == null || password.isEmpty()) {
            if (post.isProtected() && post.getPasswordHash() != null) {
                return null;
            }
            throw invalid(FieldError.of("password", "REQUIRED"));
        }
        if (password.length() < PASSWORD_MIN) {
            throw invalid(new FieldError("password", "TOO_SHORT", Map.of("min", PASSWORD_MIN)));
        }
        if (password.length() > PASSWORD_MAX) {
            throw invalid(new FieldError("password", "TOO_LONG", Map.of("max", PASSWORD_MAX)));
        }
        return passwordEncoder.encode(password);
    }

    /** 예약할지(미래 시각). 발행된 글은 422, 최대 앞날을 넘으면 400. 지금 이하이면 즉시 발행(Edge Cases). */
    private boolean schedule(Post post, Instant scheduledAt, Instant now) {
        if (scheduledAt == null || !scheduledAt.isAfter(now)) {
            return false;
        }
        if (post.getStatus() != PostStatus.DRAFT && post.getStatus() != PostStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.SCHEDULE_NOT_ALLOWED,
                    "Published post cannot be scheduled: " + post.getId());
        }
        if (scheduledAt.isAfter(now.plus(properties.scheduleMaxAhead()))) {
            throw invalid(new FieldError("scheduledAt", "INVALID",
                    Map.of("max", properties.scheduleMaxAhead().toDays())));
        }
        return true;
    }

    /** 발행 설정 → 작성 중 사본 → 지금 발행본 순으로 고른 카테고리. 이 블로그의 카테고리여야 한다. */
    private Category category(Post post, PostDraft draft, PublishSettingsRequest settings) {
        Long categoryId;
        if (settings.categoryId() != null) {
            categoryId = settings.categoryId();
        } else if (draft != null) {
            categoryId = draft.getCategoryId();
        } else {
            categoryId = post.getCategory() == null ? null : post.getCategory().getId();
        }
        return categoryId == null ? null : categoryAccess.requireInBlog(post.getBlog().getId(), categoryId);
    }

    /** 발행 설정 → 작성 중 사본 → 지금 발행본 순으로 고른 주제. 발행본과 같은 값이면 검사하지 않는다(나중에 숨겨진 주제 유지). */
    private Topic topic(Post post, PostDraft draft, PublishSettingsRequest settings) {
        Long topicId;
        if (settings.topicId() != null) {
            topicId = settings.topicId();
        } else if (draft != null) {
            topicId = draft.getTopicId();
        } else {
            topicId = post.getTopicId();
        }
        return topicService.requireSelectable(topicId, post.getTopicId());
    }

    /** 대표 이미지: 고른 키가 본문 이미지면 그것, 고르지 않았으면 본문 첫 이미지(FR-107). */
    private static String thumbnailUrl(RenderedContent content, String mediaKey) {
        if (mediaKey == null) {
            return content.firstMediaImageUrl();
        }
        if (!content.containsMediaImage(mediaKey)) {
            throw invalid(new FieldError("thumbnailMediaKey", "INVALID", Map.of()));
        }
        return "/media/" + mediaKey;
    }

    private static BusinessException invalid(FieldError error) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(error));
    }
}
